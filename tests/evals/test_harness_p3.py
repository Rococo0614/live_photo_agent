"""Offline validation of the P3 harness changes against synthetic audit rows.

Runs without a device: feeds fabricated interaction_events payloads through score()
and aggregate() and asserts the new attribution / vacuous / param-quality axes behave.

    python tests/evals/test_harness_p3.py
"""
import json
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import device_harness as H


def ev(kind, payload):
    return {"id": 1, "kind": kind, "payload": json.dumps(payload, ensure_ascii=False)}


def run(case, events):
    r = H.CaseResult(case_id=case["case_id"], query=case["user_text"],
                     category=case.get("category", "x"))
    r.events = events
    r.metrics = H.score(case, r)
    return r


# 1. Brain-authored plan pass (D013-like: color_tone, brain supplied effect, preset filled numbers)
c1 = {"case_id": "T1", "user_text": "把颜色调鲜艳一些",
      "expect": {"outcome": "plan", "tools_must_not_include": ["asset_replace"]}}
e1 = [
    ev("TRACE", "端侧大脑正在理解请求"),
    ev("INTENT_BRAIN", {"source": "qnn_genie_intent", "parsed": True, "elapsed_ms": 3000,
                        "raw_output": '{"intent":"plan","tool_calls":[{"tool":"search_assets"}]}'}),
    ev("PLAN_BRAIN", {"source": "qnn_genie", "parsed": True, "elapsed_ms": 7000,
                      "raw_output": "STEP=color_tone|鲜艳"}),
    ev("PLAN_FINAL", {"intent": "qnn_genie", "source": "qnn_genie", "clarification": None,
                      "tool_calls": [{"tool": "color_tone", "arguments": {"effect": "鲜艳"}, "reason": ""}]}),
    ev("TOOL_CALL", {"tool": "color_tone", "arguments": {"effect": "鲜艳", "saturation": 0.3, "contrast": 0.08}}),
    ev("RESULT", "导出|file:///x"),
]
r1 = run(c1, e1)
print("T1 brain pass:", r1.metrics["checks"]["passed"], r1.metrics["checks"]["brain_passed"],
      "hollow=", r1.metrics["hollow_params"], "intent_tools=", r1.metrics["intent_returned_tools"])
assert r1.metrics["checks"]["passed"] is True
assert r1.metrics["checks"]["brain_passed"] is True
assert r1.metrics["hollow_params"] == ["color_tone"]  # preset supplied the numbers
assert r1.metrics["intent_returned_tools"] is True  # INTENT_BRAIN now visible

# 2. Table-rescued pass. Post-P2a a bare CLARIFY no longer causes this: QUESTION= now parses,
# which is precisely the defect-A fix. The realistic remaining rescue is a brain timeout, so the
# fixture uses the empty output actually observed on device (90 PLAN_BRAIN rows carry none).
c2 = {"case_id": "T2", "user_text": "给这张加个复古滤镜",
      "expect": {"outcome": "plan", "tools_must_not_include": ["asset_replace"]}}
e2 = [
    ev("TRACE", "端侧大脑正在理解请求"),
    ev("PLAN_BRAIN", {"source": "qnn_genie", "parsed": False, "elapsed_ms": 30000,
                      "raw_output": ""}),
    ev("PLAN_FINAL", {"intent": "plan", "source": "fallback", "clarification": None,
                      "tool_calls": [{"tool": "live_photo_filter_effect", "arguments": {"effect": "复古"}, "reason": ""}]}),
    ev("TOOL_CALL", {"tool": "live_photo_filter_effect",
                     "arguments": {"effect": "复古", "saturation": -0.25, "temperature": 0.1}}),
    ev("RESULT", "导出|file:///y"),
]
r2 = run(c2, e2)
print("T2 rescued:", r2.metrics["checks"]["passed"], r2.metrics["checks"]["brain_passed"],
      "fallback=", r2.metrics["keyword_fallback"])
assert r2.metrics["checks"]["passed"] is True       # old metric still green
assert r2.metrics["checks"]["brain_passed"] is False  # new metric exposes the rescue
assert r2.metrics["keyword_fallback"] is True

# 3. Unresolved style (defect B: effect name no table entry matches -> silent no-op)
c3 = {"case_id": "T3", "user_text": "画面调暖一点", "expect": {"outcome": "plan"}}
e3 = [
    ev("PLAN_BRAIN", {"source": "qnn_genie", "parsed": True, "elapsed_ms": 8000,
                      "raw_output": "STEP=video_filter|调暖一点"}),
    ev("PLAN_FINAL", {"intent": "qnn_genie", "source": "qnn_genie", "clarification": None,
                      "tool_calls": [{"tool": "video_filter", "arguments": {"effect": "调暖一点"}, "reason": ""}]}),
    ev("TOOL_CALL", {"tool": "video_filter", "arguments": {"effect": "调暖一点", "contrast": 0.12, "saturation": 0.08}}),
    ev("RESULT", "导出|file:///z"),
]
r3 = run(c3, e3)
print("T3 unresolved:", r3.metrics["unresolved_style"], "hollow=", r3.metrics["hollow_params"])
# "调暖一点" contains no preset keyword (the table checks 暖色/暖调/warm), so applyStylePreset
# returns it unresolved: the user asked for warmer and gets only generic contrast defaults.
# This is defect B, and it is exactly what the aliasing fix in applyStylePreset unmasked.
assert r3.metrics["unresolved_style"] == ["video_filter"]
assert r3.metrics["hollow_params"] == []

# 4. Vacuous case (empty expect) must NOT pass
c4 = {"case_id": "T4", "user_text": "这个弄好看点", "expect": {}}
e4 = [ev("PLAN_FINAL", {"intent": "plan", "source": "fallback", "clarification": None, "tool_calls": []})]
r4 = run(c4, e4)
print("T4 vacuous:", r4.metrics["checks"]["vacuous"], "passed=", r4.metrics["checks"]["passed"])
assert r4.metrics["checks"]["vacuous"] is True
assert r4.metrics["checks"]["passed"] is None  # was True before P3.3

# 5. Clarify outcome (D015 fixed expectation)
c5 = {"case_id": "T5", "user_text": "这个弄好看点", "expect": {"outcome": "clarify"}}
e5 = [
    ev("PLAN_BRAIN", {"source": "qnn_genie", "parsed": True, "elapsed_ms": 5000,
                      "raw_output": "QUESTION=请说明要裁剪、拼接还是调色"}),
    ev("PLAN_FINAL", {"intent": "clarify", "source": "qnn_genie",
                      "clarification": "请说明要裁剪、拼接还是调色", "tool_calls": []}),
]
r5 = run(c5, e5)
print("T5 clarify:", r5.metrics["checks"]["passed"], r5.metrics["checks"]["brain_passed"],
      "clarified=", r5.metrics["clarified"])
assert r5.metrics["checks"]["passed"] is True
assert r5.metrics["checks"]["brain_passed"] is True

# 6. Chat reply must not be misread as a clarification
c6 = {"case_id": "T6", "user_text": "你好", "expect": {"outcome": "chat"}}
e6 = [
    ev("INTENT_BRAIN", {"source": "qnn_genie_intent", "parsed": True, "elapsed_ms": 2000,
                        "raw_output": '{"intent":"chat","reply":"你好，我可以帮你编辑照片"}'}),
    ev("PLAN_FINAL", {"intent": "chat", "source": "qnn_genie_intent",
                      "clarification": "你好，我可以帮你编辑照片", "tool_calls": []}),
]
r6 = run(c6, e6)
print("T6 chat:", r6.metrics["checks"]["passed"], "clarified=", r6.metrics["clarified"])
assert r6.metrics["checks"]["passed"] is True
assert r6.metrics["clarified"] is False  # intent=chat, not clarify

# 7. Protocol-clean turn under the two-slot protocol: no ACTION header, one STEP row with the
# optional @time suffix, no retired placeholder anywhere. The legacy_* probes must read 0.
c7 = {"case_id": "T7", "user_text": "把视频倒放", "expect": {"outcome": "plan", "tools_must_include": ["video_reverse"]}}
e7 = [
    ev("PLAN_BRAIN", {"source": "qnn_genie", "parsed": True, "elapsed_ms": 6500,
                      "raw_output": "STEP=video_reverse|倒放@0-30038"}),
    ev("PLAN_FINAL", {"intent": "qnn_genie", "source": "qnn_genie", "clarification": None,
                      "tool_calls": [{"tool": "video_reverse", "arguments": {"start_ms": 0, "end_ms": 30038}, "reason": "倒放"}]}),
    ev("TOOL_CALL", {"tool": "video_reverse", "arguments": {"start_ms": 0, "end_ms": 30038}}),
    ev("RESULT", "导出|file:///w"),
]
r7 = run(c7, e7)
print("T7 clean:", r7.metrics["checks"]["brain_passed"], "legacy_header=", r7.metrics["legacy_header"],
      "current_shape=", r7.metrics["current_shape_rows"], "rows=", r7.metrics["step_rows"])
assert r7.metrics["checks"]["brain_passed"] is True
assert r7.metrics["legacy_header"] is False
assert r7.metrics["legacy_field_rows"] == 0
assert r7.metrics["legacy_placeholder_leak"] == 0
assert r7.metrics["current_shape_rows"] == r7.metrics["step_rows"] == 1
assert r7.metrics["filled_slots"].get("time_suffix") == 1
# The brain emitted real numbers, so this is NOT hollow -- the style axis must stay silent.
assert r7.metrics["hollow_params"] == []

# 8. Defect B: brain gave a style name no preset entry matches, so applyStylePreset returns
# the arguments unchanged. TOOL_CALL then carries only {effect} -- the edit ships with no
# numeric parameter and silently does nothing (MobileAgentRunner.kt:797 else-branch).
c8 = {"case_id": "T8", "user_text": "调成电影感", "expect": {"outcome": "plan"}}
e8 = [
    ev("PLAN_BRAIN", {"source": "qnn_genie", "parsed": True, "elapsed_ms": 6000,
                      "raw_output": "STEP=color_tone|电影感"}),
    ev("PLAN_FINAL", {"intent": "qnn_genie", "source": "qnn_genie", "clarification": None,
                      "tool_calls": [{"tool": "color_tone", "arguments": {"effect": "电影感"}, "reason": ""}]}),
    ev("TOOL_CALL", {"tool": "color_tone", "arguments": {"effect": "电影感"}}),
    ev("RESULT", "导出|file:///v"),
]
r8 = run(c8, e8)
print("T8 defect B:", r8.metrics["unresolved_style"], "hollow=", r8.metrics["hollow_params"])
assert r8.metrics["unresolved_style"] == ["color_tone"]
assert r8.metrics["hollow_params"] == []

# 8b. The device's actual D015 behaviour: the brain clarifies through the *chat* channel, not
# ACTION=CLARIFY. intent="chat" with a reply asking for specifics still meets the contract --
# nothing was executed and the user got a question back.
c8b = {"case_id": "T8b", "user_text": "这个弄好看点", "expect": {"outcome": "clarify"}}
e8b = [
    ev("INTENT_BRAIN", {"source": "qnn_genie_intent", "parsed": True, "elapsed_ms": 6000,
                        "raw_output": '{"intent":"chat","reply":"我无法理解你的意图，请提供具体需求","tool_calls":[]}'}),
    ev("PLAN_FINAL", {"intent": "chat", "source": "qnn_genie_intent",
                      "clarification": "我无法理解你的意图，请提供具体需求", "tool_calls": []}),
    ev("AGENT", "我无法理解你的意图，请提供具体需求"),
]
r8b = run(c8b, e8b)
print("T8b chat-channel clarify:", r8b.metrics["checks"]["passed"], r8b.metrics["checks"]["brain_passed"])
assert r8b.metrics["checks"]["passed"] is True
assert r8b.metrics["checks"]["brain_passed"] is True

# 8c. The failure this check exists to catch: an ambiguous request silently guessed an edit.
c8c = {"case_id": "T8c", "user_text": "这个弄好看点", "expect": {"outcome": "clarify"}}
e8c = [
    ev("PLAN_FINAL", {"intent": "plan", "source": "fallback", "clarification": None,
                      "tool_calls": [{"tool": "portrait_beauty", "arguments": {"smooth_skin": 30}, "reason": ""}]}),
    ev("TOOL_CALL", {"tool": "portrait_beauty", "arguments": {"smooth_skin": 30}}),
    ev("RESULT", "导出|file:///u"),
]
r8c = run(c8c, e8c)
print("T8c silent guess:", r8c.metrics["checks"]["passed"])
assert r8c.metrics["checks"]["passed"] is False

# 9. Fallback turn carrying numeric style parameters: the keyword tables wrote them, not the
# brain. Before this distinction the axis labelled them brain_authored (observed on D010/D011).
c9 = {"case_id": "T9", "user_text": "给这张加个复古滤镜",
      "expect": {"outcome": "plan", "tools_must_not_include": ["asset_replace"]}}
e9 = [
    ev("PLAN_BRAIN", {"source": "qnn_genie", "parsed": False, "elapsed_ms": 30000,
                      "raw_output": ""}),
    ev("PLAN_FINAL", {"intent": "plan", "source": "fallback", "clarification": None,
                      "tool_calls": [{"tool": "live_photo_filter_effect",
                                      "arguments": {"effect": "复古", "saturation": -0.25, "temperature": 0.1},
                                      "reason": ""}]}),
    ev("TOOL_CALL", {"tool": "live_photo_filter_effect",
                     "arguments": {"effect": "复古", "saturation": -0.25, "temperature": 0.1}}),
    ev("RESULT", "导出|file:///t"),
]
r9 = run(c9, e9)
print("T9 table-authored:", r9.metrics["table_authored_params"], "brain=", r9.metrics["brain_authored_params"])
assert r9.metrics["table_authored_params"] == ["live_photo_filter_effect"]
assert r9.metrics["brain_authored_params"] == []

# 10. Legacy-template resurrection: the brain re-emits the retired six-slot shape with its
# placeholder names. This is the regression the legacy_* probes exist to surface -- it must not
# count as protocol-clean, and the echoed slots must be attributed.
c10 = {"case_id": "T10", "user_text": "视频背景虚化", "expect": {"outcome": "plan"}}
e10 = [
    ev("PLAN_BRAIN", {"source": "qnn_genie", "parsed": True, "elapsed_ms": 6000,
                      "raw_output": "ACTION=STEP|video_blur|start_ms|end_ms|素材序号|用户原话证据\n"
                                    "STEP=video_blur|start_ms|end_ms|1|视频背景虚化"}),
    ev("PLAN_FINAL", {"intent": "qnn_genie", "source": "qnn_genie", "clarification": None,
                      "tool_calls": [{"tool": "video_blur", "arguments": {}, "reason": ""}]}),
    ev("RESULT", "导出|file:///s"),
]
r10 = run(c10, e10)
print("T10 legacy resurrection: header=", r10.metrics["legacy_header"],
      "six=", r10.metrics["legacy_field_rows"], "leak=", r10.metrics["legacy_placeholder_leak"])
assert r10.metrics["legacy_header"] is True
assert r10.metrics["legacy_field_rows"] >= 1
assert r10.metrics["legacy_placeholder_leak"] >= 1
assert r10.metrics["current_shape_rows"] == 0

# aggregate across all synthetic results
summary = H.aggregate([r1, r2, r3, r4, r5, r6, r7, r8, r8b, r8c, r9, r10])
print("\n=== aggregate ===")
print(json.dumps({k: summary[k] for k in
                  ("pass_rate", "brain_pass_rate", "rescued_by_tables", "vacuous",
                   "intent_trace_complete", "intent_returned_tools", "protocol",
                   "param_quality", "latency_ms", "slot_echo")}, ensure_ascii=False, indent=1))
assert summary["vacuous"] == 1
# T4 is vacuous (passed=None) and T8c silently guessed an edit (passed=False), so 10 of 12 pass.
assert summary["pass_rate"] == 0.833
# T2 and T9 passed on the keyword tables, so the brain earned 8 of 12.
assert summary["brain_pass_rate"] == 0.667
assert summary["rescued_by_tables"] == 2
# T1, T6 and T8b carry INTENT_BRAIN rows; only T1 returned tools.
assert summary["intent_trace_complete"] == {"n": 3, "total": 12}
assert summary["intent_returned_tools"] == {"n": 1, "total": 3, "rate": 0.333}
# 6 turns carry plan-stage raw output (T2/T9 timed out with empty output, T6/T8b are chat);
# of the 5 that parsed a STEP row, only T10 is on the retired shape -> 4/5 clean. T5 is
# QUESTION-only, so it is step-class but not step-parsed and shows up in `drifted`.
assert summary["protocol"]["step_class_turns"] == 6
assert summary["protocol"]["parsed_step_rows"] == 5
assert summary["protocol"]["protocol_clean_rate"] == 0.8
assert summary["protocol"]["legacy_header_rate"] == 0.2   # T10 only
assert summary["protocol"]["legacy_field_rows"] == 1      # T10's malformed ACTION=STEP| line
assert summary["protocol"]["legacy_placeholder_leak"] == 1
# T5 is a QUESTION-only turn -- its own legitimate class, not drift. Only T10 drifted.
assert summary["protocol"]["drifted"] == ["T10"]
# T3 (unresolved 调暖一点) and T8 (unresolved 电影感) are both defect-B instances.
assert summary["param_quality"]["unresolved"] == {"T3": ["video_filter"], "T8": ["color_tone"]}
assert summary["param_quality"]["table_authored"] == {"T9": ["live_photo_filter_effect"]}
assert summary["latency_ms"]["plan"]["n"] == 8
# The effect slot is the only mandatory one left; nothing echoes the literal name "effect".
assert summary["slot_echo"]["effect"]["echo_rate"] == 0.0
# T7 filled @0-30038, T10 echoed start_ms in the effect slot: the suffix axis still sees both.
assert summary["slot_echo"]["time_suffix"] == {"echoed": 1, "filled": 1, "echo_rate": 0.5}

print("\nALL P3 ASSERTIONS PASSED")
