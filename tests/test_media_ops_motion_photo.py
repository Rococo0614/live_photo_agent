from pathlib import Path

from live_photo_agent.foundation.media_ops import MediaOps


def test_normalize_motion_photo_video_adds_silent_audio_when_missing(tmp_path: Path, monkeypatch) -> None:
    ops = MediaOps()
    monkeypatch.setattr(ops, "_ffmpeg", "/usr/bin/ffmpeg")
    monkeypatch.setattr(ops, "_ffprobe", "/usr/bin/ffprobe")

    in_path = tmp_path / "in.mp4"
    out_path = tmp_path / "out.mp4"
    in_path.write_bytes(b"video")

    recorded: list[list[str]] = []

    class _Result:
        def __init__(self, stdout: str) -> None:
            self.stdout = stdout

    def _fake_run(cmd: list[str]):
        recorded.append(cmd)
        if "-show_entries" in cmd:
            return _Result('{"streams": []}')
        return _Result("")

    monkeypatch.setattr(ops, "_run", _fake_run)

    ops.normalize_motion_photo_video(in_path, out_path)

    ffmpeg_cmd = recorded[-1]
    joined = " ".join(ffmpeg_cmd)
    assert "anullsrc=channel_layout=stereo:sample_rate=48000" in joined
    assert "-c:a" in ffmpeg_cmd
    assert "aac" in ffmpeg_cmd


def test_validate_motion_photo_jpg_detects_missing_audio(tmp_path: Path, monkeypatch) -> None:
    ops = MediaOps()
    monkeypatch.setattr(ops, "_ffmpeg", "/usr/bin/ffmpeg")
    monkeypatch.setattr(ops, "_ffprobe", "/usr/bin/ffprobe")

    # jpeg + xmp marker + minimal mp4 payload marker
    payload = (
        b"\xff\xd8"
        b"GCamera:MotionPhoto=\"1\""
        b"\xff\xd9"
        b"\x00\x00\x00\x18ftypisom\x00\x00\x02\x00isom"
    )
    file_path = tmp_path / "motion.jpg"
    file_path.write_bytes(payload)

    class _Result:
        def __init__(self, stdout: str) -> None:
            self.stdout = stdout

    def _fake_run(cmd: list[str]):
        if "-show_streams" in cmd:
            return _Result('{"streams":[{"codec_type":"video"}],"format":{"tags":{"major_brand":"mp42","com.android.version":"16"}}}')
        return _Result('{"streams": []}')

    monkeypatch.setattr(ops, "_run", _fake_run)

    report = ops.validate_motion_photo_jpg(file_path)
    assert report["valid"] is False
    assert "embedded_audio_stream" in report["missing"]


def test_validate_motion_photo_jpg_passes_with_audio(tmp_path: Path, monkeypatch) -> None:
    ops = MediaOps()
    monkeypatch.setattr(ops, "_ffmpeg", "/usr/bin/ffmpeg")
    monkeypatch.setattr(ops, "_ffprobe", "/usr/bin/ffprobe")

    payload = (
        b"\xff\xd8"
        b"VCamera:VMotionPhotoVersion"
        b"\xff\xd9"
        b"\x00\x00\x00\x18ftypisom\x00\x00\x02\x00isom"
    )
    file_path = tmp_path / "motion_ok.jpg"
    file_path.write_bytes(payload)

    class _Result:
        def __init__(self, stdout: str) -> None:
            self.stdout = stdout

    def _fake_run(cmd: list[str]):
        if "-show_streams" in cmd:
            return _Result('{"streams":[{"codec_type":"video"},{"codec_type":"audio"}],"format":{"tags":{"major_brand":"mp42","compatible_brands":"isommp42","com.android.version":"16"}}}')
        return _Result('{"streams": []}')

    monkeypatch.setattr(ops, "_run", _fake_run)

    report = ops.validate_motion_photo_jpg(file_path)
    assert report["valid"] is True