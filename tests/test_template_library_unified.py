from pathlib import Path

from live_photo_agent.foundation.retrieval.template_library import TemplateLibrary


def test_frontend_templates_are_available_from_backend_library() -> None:
    library = TemplateLibrary()
    assert library.get("live_four_grid") is not None
    assert library.get("m14_vertical_pipeline") is not None
    assert library.get("live_overlay_top") is not None


def test_custom_template_round_trips_through_shared_store(tmp_path: Path) -> None:
    library = TemplateLibrary(tmp_path / "templates")
    library.save_template_dict(
        {
            "id": "custom_round_trip",
            "name": "自定义四格",
            "style": "custom",
            "board": "xhs",
            "slots": [
                {"gx": 0, "gy": 0, "gw": 60, "gh": 80, "start_time_s": 0, "end_time_s": 3},
                {"gx": 60, "gy": 0, "gw": 60, "gh": 80, "start_time_s": 0, "end_time_s": 3},
            ],
        }
    )

    reloaded = TemplateLibrary(tmp_path / "templates")
    template = reloaded.get("custom_round_trip")
    assert template is not None
    assert template.slots[0].grid_w == 60
    assert template.slots[0].end_time_s == 3
