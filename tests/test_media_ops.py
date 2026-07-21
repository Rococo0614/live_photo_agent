from pathlib import Path

from live_photo_agent.foundation.media_ops import MediaOps


def test_compose_videos_spatial_uses_cover_crop_filters(tmp_path: Path, monkeypatch) -> None:
    media_ops = MediaOps()
    monkeypatch.setattr(media_ops, "_ffmpeg", "/usr/bin/ffmpeg")
    monkeypatch.setattr(media_ops, "_ffprobe", "/usr/bin/ffprobe")

    inputs = []
    for idx in range(3):
        path = tmp_path / f"clip_{idx}.mp4"
        path.write_bytes(b"video")
        inputs.append(path)

    captured: dict[str, object] = {}

    def _fake_run(cmd: list[str]):
        captured["cmd"] = cmd
        return None

    monkeypatch.setattr(media_ops, "_run", _fake_run)

    out = tmp_path / "out.mp4"
    media_ops.compose_videos_spatial(inputs, out, canvas="1080x1920", layout="vertical")

    cmd = captured["cmd"]
    filter_complex = cmd[cmd.index("-filter_complex") + 1]
    assert "force_original_aspect_ratio=increase" in filter_complex
    assert "crop=1080:640" in filter_complex
    assert "pad=" not in filter_complex
