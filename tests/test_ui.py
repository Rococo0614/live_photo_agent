from pathlib import Path

from starlette.testclient import TestClient

from live_photo_agent.api import app
from live_photo_agent.config import settings


def test_ui_bootstrap_and_index(tmp_path: Path, monkeypatch) -> None:
    library_root = tmp_path / "DCIM"
    album = library_root / "Camera"
    album.mkdir(parents=True)
    (album / "trip_01.jpg").write_bytes(b"\xff\xd8demo\xff\xd9")
    (album / "trip_01.mp4").write_bytes(b"\x00\x00\x00\x18ftypisom\x00\x00\x02\x00isom")

    monkeypatch.setattr(settings, "default_library_root", library_root)
    monkeypatch.setattr(settings, "album_catalog_file", tmp_path / ".album_catalog.json")
    monkeypatch.setattr(settings, "album_operation_log_file", tmp_path / ".album_operations.jsonl")
    monkeypatch.setattr(settings, "workspace_dir", tmp_path)

    client = TestClient(app)

    index_response = client.get("/")
    assert index_response.status_code == 200
    assert "Live Photo Agent Console" in index_response.text

    bootstrap_response = client.get("/api/ui/bootstrap", params={"library_root": str(library_root)})
    assert bootstrap_response.status_code == 200

    payload = bootstrap_response.json()
    assert payload["library_root"] == str(library_root.resolve())
    assert payload["summary"]["asset_count"] == 1
    assert payload["albums"][0]["album_name"] == "Camera"
    assert payload["tool_catalog"]
    assert "input_schema" in payload["tool_catalog"][0]
    assert "output_schema" in payload["tool_catalog"][0]

    media_response = client.get("/api/ui/media", params={"path": str(album / "trip_01.jpg")})
    assert media_response.status_code == 200


def test_planner_config_endpoints(monkeypatch) -> None:
    monkeypatch.setattr(settings, "planner_backend", "auto")
    monkeypatch.setattr(settings, "qwen_endpoint", None)
    monkeypatch.setattr(settings, "qwen_auth_token", None)
    monkeypatch.setattr(settings, "qwen_workspace_id", None)
    monkeypatch.setattr(settings, "qwen_model", "Qwen/Qwen3-VL-8B")
    monkeypatch.setattr(settings, "local_model_dir", None)

    client = TestClient(app)

    initial = client.get("/api/ui/planner-config")
    assert initial.status_code == 200
    initial_payload = initial.json()
    assert "runtime_info" in initial_payload

    updated = client.post(
        "/api/ui/planner-config",
        json={
            "planner_backend": "endpoint",
            "qwen_endpoint": "http://planner.test/plan",
            "qwen_model": "Qwen/Test-Model",
            "qwen_auth_token": "demo-token",
        },
    )
    assert updated.status_code == 200
    updated_payload = updated.json()
    assert updated_payload["planner_backend"] == "endpoint"
    assert updated_payload["qwen_endpoint"] == "http://planner.test/plan"
    assert updated_payload["qwen_model"] == "Qwen/Test-Model"
    assert updated_payload["qwen_auth_token_configured"] is True

    reset = client.delete("/api/ui/planner-config")
    assert reset.status_code == 200
    reset_payload = reset.json()
    assert "planner_backend" in reset_payload
    assert "runtime_info" in reset_payload