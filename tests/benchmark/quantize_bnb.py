"""Quantize Qwen2.5-7B-Instruct to int4 using bitsandbytes nf4.

This produces a model that loads in 4-bit (NF4) quantization, using ~4.5GB VRAM
instead of ~15GB. The quantization is applied at load time via BitsAndBytesConfig,
so no separate quantized model files need to be saved.

Usage:
    python tests/benchmark/quantize_bnb.py  # Just verifies the model loads in 4-bit
"""
from __future__ import annotations

import torch
from transformers import AutoModelForCausalLM, AutoTokenizer, BitsAndBytesConfig


def main():
    model_path = "/home/vivo/models/Qwen2.5-7B-Instruct"

    quant_config = BitsAndBytesConfig(
        load_in_4bit=True,
        bnb_4bit_compute_dtype=torch.float16,
        bnb_4bit_use_double_quant=True,
        bnb_4bit_quant_type="nf4",
    )

    print(f"[quantize] Loading {model_path} in NF4 int4...")
    tokenizer = AutoTokenizer.from_pretrained(model_path, trust_remote_code=True)
    model = AutoModelForCausalLM.from_pretrained(
        model_path,
        quantization_config=quant_config,
        device_map="auto",
        trust_remote_code=True,
    )

    vram_mb = torch.cuda.memory_allocated() // (1024 * 1024)
    print(f"[quantize] VRAM after load: {vram_mb}MB")

    # Quick test
    messages = [
        {"role": "system", "content": "Return only valid JSON."},
        {"role": "user", "content": 'Return {"hello": "world"}'},
    ]
    prompt = tokenizer.apply_chat_template(messages, tokenize=False, add_generation_prompt=True)
    inputs = tokenizer(prompt, return_tensors="pt").to(model.device)
    with torch.no_grad():
        out = model.generate(**inputs, max_new_tokens=50, do_sample=False)
    gen = out[:, inputs["input_ids"].shape[1]:]
    result = tokenizer.decode(gen[0], skip_special_tokens=True).strip()
    print(f"[quantize] Test output: {result}")

    # Check actual dtype of parameters
    int4_params = 0
    fp16_params = 0
    for name, param in model.named_parameters():
        if param.dtype == torch.uint8:
            int4_params += 1
        else:
            fp16_params += 1
    print(f"[quantize] int4 params: {int4_params}, other params: {fp16_params}")

    print(f"\n[quantize] Done! NF4 int4 model loaded in {vram_mb}MB VRAM")


if __name__ == "__main__":
    main()
