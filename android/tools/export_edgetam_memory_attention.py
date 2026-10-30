#!/usr/bin/env python3
"""Export fixed-count or fixed-bounded EdgeTAM memory-attention graphs.

The upstream EdgeTAM app leaves memory attention in host PyTorch. Android needs
bounded, static graphs instead. ``--num-memories`` exports a differential
variant with exact input counts. ``--bounded`` exports the preferred single
graph with seven spatial memories, sixteen object pointers, and explicit valid
bits. Complex RoPE operations are rewritten as equivalent real-valued cos/sin
operations for ONNX/QNN export.

Spatial memories are ordered as: conditioning frame, then old-to-new recent
frames. Object pointers follow EdgeTAM's selected order; their temporal
position is zero in the current checkpoint configuration.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import types
from pathlib import Path
from typing import Optional

import torch
import torch.nn.functional as F
from torch import nn

from qai_hub_models.models.edgetam.model import EdgeTAM


IMAGE_FEATURE_SIZE = 64
IMAGE_CHANNELS = 256
MEMORY_TOKENS_PER_FRAME = 512
MEMORY_CHANNELS = 64
POINTER_TOKEN_COUNT = IMAGE_CHANNELS // MEMORY_CHANNELS
MAX_SPATIAL_MEMORIES = 7
MAX_OBJECT_POINTERS = 16


def _rotate_pairs(
    value: torch.Tensor,
    cosine: torch.Tensor,
    sine: torch.Tensor,
) -> torch.Tensor:
    pairs = value.float().reshape(*value.shape[:-1], -1, 2)
    real = pairs[..., 0]
    imaginary = pairs[..., 1]
    broadcast_shape = [1] * (value.ndim - 2) + [cosine.shape[0], cosine.shape[1]]
    cosine = cosine.view(*broadcast_shape)
    sine = sine.view(*broadcast_shape)
    return torch.stack(
        (real * cosine - imaginary * sine, real * sine + imaginary * cosine),
        dim=-1,
    ).flatten(-2).to(value.dtype)


def _rotate_repeated(
    value: torch.Tensor,
    cosine: torch.Tensor,
    sine: torch.Tensor,
    repeat_count: int,
) -> torch.Tensor:
    batch, heads, tokens, channels = value.shape
    rope_tokens = cosine.shape[0]
    if tokens == rope_tokens * repeat_count:
        value_rope = value
        value_without_rope = None
        unrotated_tokens = 0
    else:
        unrotated_tokens = tokens // repeat_count - rope_tokens
        per_memory = value.view(
            batch,
            heads,
            repeat_count,
            tokens // repeat_count,
            channels,
        )
        value_without_rope = per_memory[..., :unrotated_tokens, :].reshape(
            batch, heads, -1, channels
        )
        value_rope = per_memory[..., unrotated_tokens:, :].reshape(
            batch, heads, -1, channels
        )
    if repeat_count > 1:
        cosine = cosine.repeat(repeat_count, 1)
        sine = sine.repeat(repeat_count, 1)
    rotated = _rotate_pairs(value_rope, cosine, sine)
    if value_without_rope is not None:
        rotated = torch.cat(
            (
                value_without_rope.view(
                    batch,
                    heads,
                    repeat_count,
                    unrotated_tokens,
                    channels,
                ),
                rotated.view(
                    batch,
                    heads,
                    repeat_count,
                    rope_tokens,
                    channels,
                ),
            ),
            dim=3,
        ).view(batch, heads, tokens, channels)
    return rotated


def _self_attention_forward(
    self: nn.Module,
    q: torch.Tensor,
    k: torch.Tensor,
    v: torch.Tensor,
) -> torch.Tensor:
    query = self._separate_heads(self.q_proj(q), self.num_heads)
    key = self._separate_heads(self.k_proj(k), self.num_heads)
    value = self._separate_heads(self.v_proj(v), self.num_heads)
    query = _rotate_pairs(query, self.qai_cosine, self.qai_sine)
    key = _rotate_pairs(key, self.qai_cosine, self.qai_sine)
    attended = F.scaled_dot_product_attention(
        query,
        key,
        value,
        dropout_p=0.0,
    )
    return self.out_proj(self._recombine_heads(attended))


def _cross_attention_forward(
    self: nn.Module,
    q: torch.Tensor,
    k: torch.Tensor,
    v: torch.Tensor,
    num_k_exclude_rope: int = 0,
    rope_k_repeat: int = -1,
    attention_bias: Optional[torch.Tensor] = None,
) -> torch.Tensor:
    query = self._separate_heads(self.q_proj(q), self.num_heads)
    key = self._separate_heads(self.k_proj(k), self.num_heads)
    value = self._separate_heads(self.v_proj(v), self.num_heads)
    query = _rotate_repeated(
        query,
        self.qai_query_cosine,
        self.qai_query_sine,
        1,
    )
    rotated_key_count = key.shape[-2] - num_k_exclude_rope
    rotated_key = _rotate_repeated(
        key[:, :, :rotated_key_count],
        self.qai_key_cosine,
        self.qai_key_sine,
        rope_k_repeat,
    )
    key = torch.cat((rotated_key, key[:, :, rotated_key_count:]), dim=2)
    attended = F.scaled_dot_product_attention(
        query,
        key,
        value,
        attn_mask=attention_bias,
        dropout_p=0.0,
    )
    return self.out_proj(self._recombine_heads(attended))


def patch_real_rope(sam2: nn.Module) -> None:
    """Replace complex RoPE with an exactly equivalent real implementation."""
    for layer in sam2.memory_attention.layers:
        self_attention = layer.self_attn
        self_frequency = self_attention.compute_cis(
            end_x=IMAGE_FEATURE_SIZE,
            end_y=IMAGE_FEATURE_SIZE,
        )
        self_attention.register_buffer("qai_cosine", self_frequency.real.float())
        self_attention.register_buffer("qai_sine", self_frequency.imag.float())
        self_attention.forward = types.MethodType(
            _self_attention_forward,
            self_attention,
        )

        cross_attention = layer.cross_attn_image
        cross_attention.register_buffer(
            "qai_query_cosine",
            cross_attention.freqs_cis_q.real.float(),
        )
        cross_attention.register_buffer(
            "qai_query_sine",
            cross_attention.freqs_cis_q.imag.float(),
        )
        cross_attention.register_buffer(
            "qai_key_cosine",
            cross_attention.freqs_cis_k.real.float(),
        )
        cross_attention.register_buffer(
            "qai_key_sine",
            cross_attention.freqs_cis_k.imag.float(),
        )
        cross_attention.forward = types.MethodType(
            _cross_attention_forward,
            cross_attention,
        )


class FixedMemoryAttention(nn.Module):
    def __init__(
        self,
        sam2: nn.Module,
        current_position: torch.Tensor,
        mask_position: torch.Tensor,
        num_memories: int,
        num_object_pointers: int,
    ) -> None:
        super().__init__()
        self.attention = sam2.memory_attention
        self.num_memories = num_memories
        self.num_object_pointers = num_object_pointers
        self.register_buffer("current_position", current_position)

        # Conditioning memory always has t_pos=0 -> index num_maskmem - 1.
        # During startup, recent memories occupy the newest temporal slots.
        temporal_indices = [sam2.num_maskmem - 1] + list(
            range(num_memories - 2, -1, -1)
        )
        base_position = mask_position.permute(1, 0, 2)
        spatial_positions = [
            base_position + sam2.maskmem_tpos_enc[index].view(1, 1, -1)
            for index in temporal_indices
        ]
        self.register_buffer("spatial_position", torch.cat(spatial_positions, dim=0))

    def forward(
        self,
        current_embeddings: torch.Tensor,
        maskmem_features: torch.Tensor,
        object_pointers: torch.Tensor,
    ) -> torch.Tensor:
        batch, channels, height, width = current_embeddings.shape
        current = current_embeddings.flatten(2).permute(2, 0, 1)
        spatial_memory = maskmem_features.permute(1, 2, 0, 3).reshape(
            self.num_memories * MEMORY_TOKENS_PER_FRAME,
            batch,
            MEMORY_CHANNELS,
        )
        pointer_memory = object_pointers.permute(1, 0, 2).reshape(
            self.num_object_pointers,
            batch,
            POINTER_TOKEN_COUNT,
            MEMORY_CHANNELS,
        ).permute(0, 2, 1, 3).flatten(0, 1)
        memory = torch.cat((spatial_memory, pointer_memory), dim=0)
        memory_position = torch.cat(
            (
                self.spatial_position.expand(-1, batch, -1),
                torch.zeros_like(pointer_memory),
            ),
            dim=0,
        )
        output = self.attention(
            curr=[current],
            curr_pos=[self.current_position.expand(-1, batch, -1)],
            memory=memory,
            memory_pos=memory_position,
            num_obj_ptr_tokens=self.num_object_pointers * POINTER_TOKEN_COUNT,
            num_spatial_mem=self.num_memories,
        )
        return output.permute(1, 2, 0).reshape(batch, channels, height, width)


class BoundedMemoryAttention(nn.Module):
    """One static graph covering all EdgeTAM memory and pointer counts.

    Spatial slots map to temporal-position indices [6, 5, ..., 0]. The
    conditioning memory occupies slot 0; available non-conditioning memories
    occupy the newest trailing slots. Object pointers occupy the leading valid
    pointer slots. Invalid slots are excluded from every cross-attention layer.
    """

    def __init__(
        self,
        sam2: nn.Module,
        current_position: torch.Tensor,
        mask_position: torch.Tensor,
    ) -> None:
        super().__init__()
        if sam2.num_maskmem != MAX_SPATIAL_MEMORIES:
            raise ValueError(
                f"expected num_maskmem={MAX_SPATIAL_MEMORIES}, "
                f"got {sam2.num_maskmem}"
            )
        if sam2.max_obj_ptrs_in_encoder != MAX_OBJECT_POINTERS:
            raise ValueError(
                f"expected max_obj_ptrs_in_encoder={MAX_OBJECT_POINTERS}, "
                f"got {sam2.max_obj_ptrs_in_encoder}"
            )
        if sam2.add_tpos_enc_to_obj_ptrs:
            raise ValueError("object-pointer temporal encoding is not supported")
        self.attention = sam2.memory_attention
        self.register_buffer("current_position", current_position)
        base_position = mask_position.permute(1, 0, 2)
        spatial_positions = [
            base_position + sam2.maskmem_tpos_enc[index].view(1, 1, -1)
            for index in range(MAX_SPATIAL_MEMORIES - 1, -1, -1)
        ]
        self.register_buffer("spatial_position", torch.cat(spatial_positions, dim=0))

    def forward(
        self,
        current_embeddings: torch.Tensor,
        maskmem_features: torch.Tensor,
        object_pointers: torch.Tensor,
        spatial_memory_valid: torch.Tensor,
        object_pointer_valid: torch.Tensor,
    ) -> torch.Tensor:
        batch, channels, height, width = current_embeddings.shape
        current = current_embeddings.flatten(2).permute(2, 0, 1)
        spatial_memory = maskmem_features.permute(1, 2, 0, 3).reshape(
            MAX_SPATIAL_MEMORIES * MEMORY_TOKENS_PER_FRAME,
            batch,
            MEMORY_CHANNELS,
        )
        pointer_memory = object_pointers.permute(1, 0, 2).reshape(
            MAX_OBJECT_POINTERS,
            batch,
            POINTER_TOKEN_COUNT,
            MEMORY_CHANNELS,
        ).permute(0, 2, 1, 3).flatten(0, 1)
        memory = torch.cat((spatial_memory, pointer_memory), dim=0)
        memory_position = torch.cat(
            (
                self.spatial_position.expand(-1, batch, -1),
                torch.zeros_like(pointer_memory),
            ),
            dim=0,
        )

        spatial_token_valid = spatial_memory_valid.unsqueeze(-1).expand(
            -1,
            -1,
            MEMORY_TOKENS_PER_FRAME,
        ).reshape(batch, -1)
        pointer_token_valid = object_pointer_valid.unsqueeze(-1).expand(
            -1,
            -1,
            POINTER_TOKEN_COUNT,
        ).reshape(batch, -1)
        token_valid = torch.cat(
            (spatial_token_valid, pointer_token_valid),
            dim=1,
        )
        # The host contract validates these tensors as exact 0/1 values. Keeping
        # them as float inputs avoids a bool boundary conversion in QNN.
        attention_bias = (1.0 - token_valid.float()).unsqueeze(1).unsqueeze(1)
        attention_bias = attention_bias * -10000.0

        output = current
        current_position = self.current_position.expand(-1, batch, -1)
        if self.attention.pos_enc_at_input:
            output = output + 0.1 * current_position
        if self.attention.batch_first:
            output = output.transpose(0, 1)
            current_position = current_position.transpose(0, 1)
            memory = memory.transpose(0, 1)
            memory_position = memory_position.transpose(0, 1)

        for layer in self.attention.layers:
            normalized = layer.norm1(output)
            query = (
                normalized + current_position
                if layer.pos_enc_at_attn
                else normalized
            )
            attended = layer.self_attn(query, query, v=normalized)
            output = output + layer.dropout1(attended)

            normalized = layer.norm2(output)
            cross_query = (
                normalized + current_position
                if layer.pos_enc_at_cross_attn_queries
                else normalized
            )
            cross_key = (
                memory + memory_position
                if layer.pos_enc_at_cross_attn_keys
                else memory
            )
            attended = layer.cross_attn_image(
                q=cross_query,
                k=cross_key,
                v=memory,
                num_k_exclude_rope=(
                    MAX_OBJECT_POINTERS * POINTER_TOKEN_COUNT
                ),
                rope_k_repeat=MAX_SPATIAL_MEMORIES,
                attention_bias=attention_bias,
            )
            output = output + layer.dropout2(attended)

            normalized = layer.norm3(output)
            feed_forward = layer.linear2(
                layer.dropout(layer.activation(layer.linear1(normalized)))
            )
            output = output + layer.dropout3(feed_forward)

        output = self.attention.norm(output)
        if self.attention.batch_first:
            output = output.transpose(0, 1)
        return output.permute(1, 2, 0).reshape(batch, channels, height, width)


def current_position_encoding(sam2: nn.Module) -> torch.Tensor:
    with torch.no_grad():
        backbone = sam2.forward_image(
            torch.zeros(1, 3, sam2.image_size, sam2.image_size)
        )
    return backbone["vision_pos_enc"][-1].flatten(2).permute(2, 0, 1)


def build_model(
    num_memories: int,
    num_object_pointers: int,
    real_rope: bool,
) -> FixedMemoryAttention:
    collection = EdgeTAM.from_pretrained()
    collection.sam2.eval()
    position = current_position_encoding(collection.sam2)
    if real_rope:
        patch_real_rope(collection.sam2)
    return FixedMemoryAttention(
        collection.sam2,
        position,
        collection.memory_encoder.maskmem_pos_enc,
        num_memories,
        num_object_pointers,
    ).eval()


def build_bounded_model() -> BoundedMemoryAttention:
    collection = EdgeTAM.from_pretrained()
    collection.sam2.eval()
    position = current_position_encoding(collection.sam2)
    patch_real_rope(collection.sam2)
    return BoundedMemoryAttention(
        collection.sam2,
        position,
        collection.memory_encoder.maskmem_pos_enc,
    ).eval()


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument("--num-memories", type=int)
    mode.add_argument("--bounded", action="store_true")
    parser.add_argument("--num-object-pointers", type=int)
    parser.add_argument("--output", required=True, type=Path)
    parser.add_argument("--summary", type=Path)
    parser.add_argument("--seed", type=int, default=7)
    args = parser.parse_args()
    if args.bounded:
        if args.num_object_pointers is not None:
            parser.error("--num-object-pointers cannot be used with --bounded")
    else:
        if not 1 <= args.num_memories <= MAX_SPATIAL_MEMORIES:
            parser.error("--num-memories must be in [1, 7]")
        if args.num_object_pointers is None:
            args.num_object_pointers = args.num_memories
        if not 1 <= args.num_object_pointers <= MAX_OBJECT_POINTERS:
            parser.error("--num-object-pointers must be in [1, 16]")
    return args


def _fixed_sample_inputs(
    num_memories: int,
    num_object_pointers: int,
) -> tuple[torch.Tensor, ...]:
    return (
        torch.randn(1, IMAGE_CHANNELS, IMAGE_FEATURE_SIZE, IMAGE_FEATURE_SIZE),
        torch.randn(
            1,
            num_memories,
            MEMORY_TOKENS_PER_FRAME,
            MEMORY_CHANNELS,
        ),
        torch.randn(1, num_object_pointers, IMAGE_CHANNELS),
    )


def _pad_bounded_inputs(
    fixed_inputs: tuple[torch.Tensor, ...],
) -> tuple[torch.Tensor, ...]:
    current, compact_memory, compact_pointers = fixed_inputs
    num_memories = compact_memory.shape[1]
    num_pointers = compact_pointers.shape[1]
    bounded_memory = compact_memory.new_zeros(
        1,
        MAX_SPATIAL_MEMORIES,
        MEMORY_TOKENS_PER_FRAME,
        MEMORY_CHANNELS,
    )
    bounded_memory[:, 0] = compact_memory[:, 0]
    if num_memories > 1:
        bounded_memory[:, -(num_memories - 1):] = compact_memory[:, 1:]
    bounded_pointers = compact_pointers.new_zeros(
        1,
        MAX_OBJECT_POINTERS,
        IMAGE_CHANNELS,
    )
    bounded_pointers[:, :num_pointers] = compact_pointers
    spatial_valid = compact_memory.new_zeros(1, MAX_SPATIAL_MEMORIES)
    spatial_valid[:, 0] = 1.0
    if num_memories > 1:
        spatial_valid[:, -(num_memories - 1):] = 1.0
    pointer_valid = compact_pointers.new_zeros(1, MAX_OBJECT_POINTERS)
    pointer_valid[:, :num_pointers] = 1.0
    return (
        current,
        bounded_memory,
        bounded_pointers,
        spatial_valid,
        pointer_valid,
    )


def _export_bounded(args: argparse.Namespace) -> dict[str, object]:
    bounded = build_bounded_model()
    validation = {}
    export_inputs = None
    for num_memories, num_pointers in ((1, 1), (2, 2), (7, 16)):
        fixed_inputs = _fixed_sample_inputs(num_memories, num_pointers)
        reference = build_model(
            num_memories,
            num_pointers,
            real_rope=False,
        )
        bounded_inputs = _pad_bounded_inputs(fixed_inputs)
        with torch.no_grad():
            expected = reference(*fixed_inputs)
            actual = bounded(*bounded_inputs)
        difference = (expected - actual).abs()
        if not torch.allclose(expected, actual, atol=1e-5, rtol=1e-5):
            raise RuntimeError(
                "bounded attention is not equivalent for "
                f"{num_memories} memories/{num_pointers} pointers: "
                f"max_abs={float(difference.max())}"
            )
        validation[f"memory_{num_memories}_pointer_{num_pointers}"] = {
            "max_abs": float(difference.max()),
            "mean_abs": float(difference.mean()),
        }
        if num_memories == MAX_SPATIAL_MEMORIES:
            export_inputs = bounded_inputs

    if export_inputs is None:
        raise RuntimeError("missing bounded export inputs")
    exported = torch.export.export(bounded, export_inputs, strict=True)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    torch.export.save(exported, args.output)

    # Ensure validity inputs remain live after export, not specialized constants.
    fixed_inputs = _fixed_sample_inputs(1, 1)
    bounded_inputs = _pad_bounded_inputs(fixed_inputs)
    reference = build_model(1, 1, real_rope=False)
    with torch.no_grad():
        expected = reference(*fixed_inputs)
        exported_actual = exported.module()(*bounded_inputs)
    exported_difference = (expected - exported_actual).abs()
    if not torch.allclose(expected, exported_actual, atol=1e-5, rtol=1e-5):
        raise RuntimeError(
            "exported bounded graph did not preserve validity inputs: "
            f"max_abs={float(exported_difference.max())}"
        )
    validation["exported_memory_1_pointer_1"] = {
        "max_abs": float(exported_difference.max()),
        "mean_abs": float(exported_difference.mean()),
    }
    return {
        "schema_version": 1,
        "mode": "bounded",
        "max_spatial_memories": MAX_SPATIAL_MEMORIES,
        "max_object_pointers": MAX_OBJECT_POINTERS,
        "validation": validation,
        "output": str(args.output),
        "size_bytes": args.output.stat().st_size,
        "sha256": hashlib.sha256(args.output.read_bytes()).hexdigest(),
        "input_shapes": {
            "current_embeddings": [1, IMAGE_CHANNELS, 64, 64],
            "maskmem_features": [
                1,
                MAX_SPATIAL_MEMORIES,
                MEMORY_TOKENS_PER_FRAME,
                MEMORY_CHANNELS,
            ],
            "object_pointers": [1, MAX_OBJECT_POINTERS, IMAGE_CHANNELS],
            "spatial_memory_valid": [1, MAX_SPATIAL_MEMORIES],
            "object_pointer_valid": [1, MAX_OBJECT_POINTERS],
        },
    }


def _export_fixed(args: argparse.Namespace) -> dict[str, object]:
    sample_inputs = _fixed_sample_inputs(
        args.num_memories,
        args.num_object_pointers,
    )

    reference = build_model(
        args.num_memories,
        args.num_object_pointers,
        real_rope=False,
    )
    exportable = build_model(
        args.num_memories,
        args.num_object_pointers,
        real_rope=True,
    )
    with torch.no_grad():
        expected = reference(*sample_inputs)
        actual = exportable(*sample_inputs)
    difference = (expected - actual).abs()
    if not torch.allclose(expected, actual, atol=1e-5, rtol=1e-5):
        raise RuntimeError(
            "real-valued RoPE is not equivalent: "
            f"max_abs={float(difference.max())}"
        )

    exported = torch.export.export(exportable, sample_inputs, strict=True)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    torch.export.save(exported, args.output)
    return {
        "schema_version": 1,
        "mode": "fixed",
        "num_memories": args.num_memories,
        "num_object_pointers": args.num_object_pointers,
        "real_rope_max_abs_vs_original": float(difference.max()),
        "real_rope_mean_abs_vs_original": float(difference.mean()),
        "output": str(args.output),
        "size_bytes": args.output.stat().st_size,
        "sha256": hashlib.sha256(args.output.read_bytes()).hexdigest(),
        "input_shapes": {
            "current_embeddings": [1, IMAGE_CHANNELS, 64, 64],
            "maskmem_features": [
                1,
                args.num_memories,
                MEMORY_TOKENS_PER_FRAME,
                MEMORY_CHANNELS,
            ],
            "object_pointers": [1, args.num_object_pointers, IMAGE_CHANNELS],
        },
    }


def main() -> None:
    args = parse_args()
    torch.manual_seed(args.seed)
    summary = _export_bounded(args) if args.bounded else _export_fixed(args)
    if args.summary:
        args.summary.parent.mkdir(parents=True, exist_ok=True)
        args.summary.write_text(json.dumps(summary, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(summary, indent=2))


if __name__ == "__main__":
    main()
