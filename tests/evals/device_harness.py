"""On-device planner harness.

Drives the real APK over adb and scores the on-device brain from the audit log.
`tests/evals/run_planner_eval.py` exercises the Python planner in `src/`, which is
not the code that ships: the device runs `GenieTextPlanner` (Kotlin) against a QNN
HTP Qwen, and only that pairing reproduces the template-echo and CHAT-swallow
behaviour.

Requires the ADBKeyboard IME -- `adb shell input text` cannot emit CJK, it writes
the percent-escapes literally. Enable once with:

    adb install ADBKeyboard.apk
    adb shell ime enable com.android.adbkeyboard/.AdbIME
    adb shell ime set com.android.adbkeyboard/.AdbIME

Usage:
    python tests/evals/device_harness.py                 # full set
    python tests/evals/device_harness.py --repeat 3      # sampler variance
    python tests/evals/device_harness.py --category chat
"""

from __future__ import annotations

import argparse
import base64
import json
import re
import subprocess
import sys
import time
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

DB = "/data/user/0/com.vivo.lpa/databases/live_photo_agent.db"
PKG = "com.vivo.lpa"
ACTIVITY = f"{PKG}/.AgentActivity"
IME = "com.android.adbkeyboard/.AdbIME"

ROOT = Path(__file__).resolve().parent
EVAL_SET = ROOT / "device_eval_set.jsonl"
REPORT = ROOT / "device_eval_report.json"
FAILURES = ROOT / "device_eval_failures.jsonl"

NODE_RE = re.compile(r"<node[^>]*>")
ATTR_RE = {k: re.compile(rf'{k}="([^"]*)"') for k in ("class", "text", "bounds", "resource-id", "content-desc")}
BOUNDS_RE = re.compile(r"\[(\d+),(\d+)\]\[(\d+),(\d+)\]")


@dataclass
class Node:
    cls: str
    text: str
    bounds: str
    clickable: bool
    focused: bool
    raw: str

    @property
    def center(self) -> tuple[int, int] | None:
        m = BOUNDS_RE.match(self.bounds)
        if not m:
            return None
        x1, y1, x2, y2 = (int(g) for g in m.groups())
        return (x1 + x2) // 2, (y1 + y2) // 2


@dataclass
class CaseResult:
    case_id: str
    query: str
    category: str
    repeat: int = 0
    events: list[dict[str, Any]] = field(default_factory=list)
    timed_out: bool = False
    error: str | None = None
    metrics: dict[str, Any] = field(default_factory=dict)


def sh(args: list[str], timeout: int = 60) -> str:
    r = subprocess.run(["adb", "shell", *args], capture_output=True, text=True, timeout=timeout)
    return r.stdout


def sql(query: str, timeout: int = 60) -> str:
    """Run one sqlite3 statement, feeding it over stdin.

    Quoting the statement for the device shell is not viable: doubling the inner
    quotes (needed for `replace(payload, char(10), ' ')`) makes the shell split the
    argument at the spaces, and sqlite3 then silently returns nothing.
    """
    r = subprocess.run(["adb", "shell", "sqlite3", DB], input=query,
                       capture_output=True, text=True, timeout=timeout)
    return r.stdout.replace("\r", "")


def max_event_id() -> int:
    out = sql("SELECT MAX(id) FROM interaction_events").strip()
    return int(out) if out.isdigit() else 0


def dump_ui() -> list[Node]:
    sh(["uiautomator", "dump", "/sdcard/lpa_ui.xml"])
    xml = sh(["cat", "/sdcard/lpa_ui.xml"])
    nodes = []
    for m in NODE_RE.finditer(xml):
        raw = m.group(0)
        attr = {k: (rx.search(raw).group(1) if rx.search(raw) else "") for k, rx in ATTR_RE.items()}
        nodes.append(Node(
            cls=attr["class"],
            text=attr["text"].replace("&#10;", "\n"),
            bounds=attr["bounds"],
            clickable='clickable="true"' in raw,
            focused='focused="true"' in raw,
            raw=raw,
        ))
    return nodes


def find(nodes: list[Node], text: str | None = None, cls: str | None = None) -> Node | None:
    for n in nodes:
        if text is not None and n.text.strip() != text:
            continue
        if cls is not None and not n.cls.endswith(cls):
            continue
        return n
    return None


def tap(node: Node | None) -> bool:
    if not node or not node.center:
        return False
    x, y = node.center
    sh(["input", "tap", str(x), str(y)])
    return True


def ensure_awake() -> None:
    """Wake the screen and hold it on for the rest of the run.

    A sleeping device reports mCurrentFocus=null, so ensure_foreground() relaunches the app
    into a screen that is still off and every case then fails as "无法新建会话" -- an
    infrastructure fault wearing a session-management error message. It burned a whole 20x3
    run that way. Wake first, and keep the display on so a long run cannot fall asleep
    between cases.
    """
    power = sh(["dumpsys", "power"])
    if "mWakefulness=Awake" not in power:
        sh(["input", "keyevent", "KEYCODE_WAKEUP"])
        time.sleep(2)
    sh(["svc", "power", "stayon", "true"])
    sh(["wm", "dismiss-keyguard"])
    if "mWakefulness=Awake" not in sh(["dumpsys", "power"]):
        raise RuntimeError("设备无法唤醒（mWakefulness != Awake），拒绝启动评测")


def ensure_foreground() -> None:
    focus = sh(["dumpsys", "window"]).split("mCurrentFocus=")[-1].split("\n")[0]
    if ACTIVITY.split("/")[-1] in focus:
        return
    sh(["monkey", "-p", PKG, "-c", "android.intent.category.LAUNCHER", "1"])
    time.sleep(10)
    # Cold start lands on a resume/new-session dialog; either button reaches the chat UI.
    for label in ("新建对话", "继续最近对话"):
        node = find(dump_ui(), text=label)
        if node and tap(node):
            time.sleep(6)
            return


def ensure_ime() -> None:
    current = sh(["settings", "get", "secure", "default_input_method"]).strip()
    if current == IME:
        return
    sh(["ime", "enable", IME])
    sh(["ime", "set", IME])
    time.sleep(1)


def new_session() -> bool:
    """Open a blank session so session history cannot leak between cases.

    The planner feeds up to 8 past turns into its prompt, so a case run after an
    unrelated one is not measuring the same thing.

    The app can be in two states here: the main chat screen, where 新建对话 sits
    behind the 会话 button, or a 选择对话 dialog that already offers it. Both are
    handled by looking for the button before trying to open the menu.
    """
    ensure_foreground()
    for _ in range(2):
        nodes = dump_ui()
        if tap(find(nodes, text="新建对话")):
            time.sleep(5)
            return _on_chat_screen()
        # A 取消 button means a dialog is up without a 新建对话 (e.g. 管理 mode).
        if tap(find(nodes, text="取消")):
            time.sleep(2)
            continue
        if tap(find(nodes, text="会话")):
            time.sleep(2)
            continue
        return False
    return False


def _on_chat_screen() -> bool:
    """True once the input box is reachable, i.e. no dialog is covering it."""
    for _ in range(3):
        nodes = dump_ui()
        if find(nodes, cls="EditText") and find(nodes, text="发送"):
            return True
        # A cold start can land back on the resume/new-session dialog.
        if tap(find(nodes, text="新建对话")) or tap(find(nodes, text="继续最近对话")):
            time.sleep(4)
            continue
        return False
    return False


def type_query(text: str) -> bool:
    """Focus the input, clear it, then push CJK-safe text through the IME broadcast."""
    nodes = dump_ui()
    box = find(nodes, cls="EditText")
    if not box or not tap(box):
        return False
    time.sleep(2)
    sh(["am", "broadcast", "-a", "ADB_CLEAR_TEXT"])
    time.sleep(1)
    payload = base64.b64encode(text.encode("utf-8")).decode("ascii")
    sh(["am", "broadcast", "-a", "ADB_INPUT_B64", "--es", "msg", payload])
    time.sleep(2)
    box = find(dump_ui(), cls="EditText")
    return bool(box and box.text.strip() == text.strip())


def send() -> bool:
    # The soft keyboard shifts the layout up, so re-read bounds instead of reusing
    # coordinates captured before the input was focused.
    node = find(dump_ui(), text="发送")
    return tap(node)


def wait_terminal(after_id: int, timeout: int) -> tuple[list[dict[str, Any]], bool]:
    """Block until the turn settles, then return its audit rows.

    Settled = an AGENT or RESULT row exists and the id has not advanced for three
    consecutive polls. Polling only ids keeps this cheap while an export runs.
    """
    deadline = time.time() + timeout
    last = after_id
    stable = 0
    while time.time() < deadline:
        time.sleep(3)
        cur = max_event_id()
        if cur == last:
            stable += 1
            kinds = sql(
                f"SELECT kind FROM interaction_events WHERE id>{after_id} "
                "AND kind IN ('AGENT','RESULT')"
            )
            if stable >= 3 and kinds.strip():
                break
        else:
            stable = 0
            last = cur
    # char(31) as field separator: sqlite3 does not interpret \x escapes, and payload
    # text is free to contain '|' which would corrupt a naive split.
    rows = sql(
        f"SELECT id || char(31) || kind || char(31) || "
        f"replace(replace(payload, char(10), ' '), char(13), ' ') "
        f"FROM interaction_events WHERE id>{after_id} ORDER BY id"
    )
    events = []
    for line in rows.split("\n"):
        line = line.rstrip("\r")
        if not line.strip():
            continue
        parts = line.split("\x1f", 2)
        if len(parts) < 3 or not parts[0].isdigit():
            continue
        events.append({"id": int(parts[0]), "kind": parts[1], "payload": parts[2]})
    return events, time.time() >= deadline and stable < 3


def run_case(case: dict[str, Any], repeat: int, timeout: int, fresh_session: bool) -> CaseResult:
    result = CaseResult(
        case_id=case["case_id"],
        query=case["user_text"],
        category=case.get("category", "uncategorized"),
        repeat=repeat,
    )
    try:
        if fresh_session and not new_session():
            result.error = "无法新建会话"
            return result
        ensure_ime()
        after = max_event_id()
        if not type_query(case["user_text"]):
            result.error = "输入失败（EditText 未获焦或文本不匹配）"
            return result
        if not send():
            result.error = "找不到发送按钮"
            return result
        result.events, result.timed_out = wait_terminal(after, timeout)
        if not result.events:
            result.error = "未捕获到任何审计行（发送可能未生效或水位线错位）"
            return result
        result.metrics = score(case, result)
    except subprocess.TimeoutExpired:
        result.error = "adb 超时"
    except Exception as exc:  # keep the run going: one flaky case must not kill the sweep
        result.error = f"{type(exc).__name__}: {exc}"
    return result


def _json_payload(payload: str) -> dict[str, Any]:
    try:
        return json.loads(payload)
    except json.JSONDecodeError:
        return {}


def _intent_tool_calls(raw_output: str) -> list[Any]:
    """Parse the JSON-protocol (intent stage) brain output.

    The brain wraps its JSON in a leading newline and occasionally prose, so the
    outermost braces are sliced out the same way GenieTextPlanner does.
    """
    m = re.search(r"\{.*\}", raw_output or "", re.S)
    if not m:
        return []
    return _json_payload(m.group(0)).get("tool_calls") or []


def score(case: dict[str, Any], result: CaseResult) -> dict[str, Any]:
    """Deterministic scoring straight off the audit rows.

    PLAN_BRAIN is only written when onPlan fires, so the intent-stage call is
    counted from its TRACE line instead -- counting PLAN_BRAIN rows would silently
    drop every intent call that fell through to retrieval.
    """
    kinds = [e["kind"] for e in result.events]
    traces = [e["payload"] for e in result.events if e["kind"] == "TRACE"]
    brains = [_json_payload(e["payload"]) for e in result.events if e["kind"] == "PLAN_BRAIN"]
    intents = [_json_payload(e["payload"]) for e in result.events if e["kind"] == "INTENT_BRAIN"]
    finals = [_json_payload(e["payload"]) for e in result.events if e["kind"] == "PLAN_FINAL"]
    tools = [t for f in finals for t in f.get("tool_calls", [])]
    tool_names = [t.get("tool") for t in tools]

    intent_called = any("端侧大脑正在理解请求" in t for t in traces)
    # INTENT_BRAIN is logged for every intent attempt, parsed or not. Before it existed the
    # only intent rows in PLAN_BRAIN were the ones that took the chat fast-path, which made
    # intent_returned_tools structurally unmeasurable (planner_reliability_plan.md §3.4).
    # Older reports have no INTENT_BRAIN rows, so fall back to the legacy PLAN_BRAIN source.
    intent_brain = intents[-1] if intents else next(
        (b for b in brains if b.get("source") == "qnn_genie_intent"), None)
    step_brain = next((b for b in brains if b.get("source") == "qnn_genie"), None)

    final = finals[-1] if finals else {}
    # PLAN_FINAL.source is "fallback" exactly when the brain ran but failed to parse and the
    # keyword tables rescued the turn (AgentActivity.kt:700). This is the attribution signal:
    # a pass earned by the tables is not a pass earned by the brain.
    final_source = final.get("source", "none")
    # Chat replies and clarification questions share MobilePlan.clarification, so the intent
    # field is what tells them apart (GenieTextPlanner.parse maps QUESTION= to intent
    # "clarify" and REPLY= to intent "chat"; the field name itself is the discriminator,
    # there is no ACTION header).
    final_intent = final.get("intent")
    clarification = final.get("clarification")
    clarified = final_intent == "clarify" and bool(clarification)
    executed = "RESULT" in kinds or any("导出" in t for t in traces)
    # A chat-path reply is only a swallow when the turn was supposed to edit. Gating
    # on the expected outcome is what separates the real regression ("做个狗的四拼"
    # answered with prose) from correct behaviour ("你好" answered with prose).
    wants_edit = (case.get("expect", {}).get("outcome") == "plan")
    swallowed = wants_edit and bool(intent_brain) and not tool_names and not executed
    intent_elapsed = (intent_brain or {}).get("elapsed_ms")
    plan_elapsed = (step_brain or {}).get("elapsed_ms")
    turn_elapsed = sum(v for v in (intent_elapsed, plan_elapsed) if isinstance(v, (int, float)))

    metrics: dict[str, Any] = {
        "events_seen": len(result.events),
        "intent_called": intent_called,
        "intent_returned_tools": bool(intent_brain) and bool(
            _intent_tool_calls(intent_brain.get("raw_output", ""))),
        # Only meaningful once INTENT_BRAIN rows exist; on legacy reports the intent sample
        # is chat-fast-path-only and this field must not be read as a rate.
        "intent_trace_complete": bool(intents),
        "chat_swallowed_edit": swallowed,
        "retrieved": any("语义检索" in t for t in traces),
        "keyword_fallback": final_source == "fallback",
        "final_source": final_source,
        "clarified": clarified,
        "clarification": clarification if clarified else None,
        "planned_tools": tool_names,
        "executed": executed,
        "step_raw": (step_brain or {}).get("raw_output", ""),
        "intent_raw": (intent_brain or {}).get("raw_output", ""),
        "latency_ms": {
            "turn": turn_elapsed or None,
            "intent": intent_elapsed,
            "plan": plan_elapsed,
            "intent_reasoning": (intent_brain or {}).get("reasoning_elapsed_ms"),
            "intent_format": (intent_brain or {}).get("format_elapsed_ms"),
            "plan_reasoning": (step_brain or {}).get("reasoning_elapsed_ms"),
            "plan_format": (step_brain or {}).get("format_elapsed_ms"),
        },
        "planner_strategy": (step_brain or intent_brain or {}).get("strategy", "legacy"),
        "reasoning_chars": {
            "intent": len((intent_brain or {}).get("reasoning_output") or ""),
            "plan": len((step_brain or {}).get("reasoning_output") or ""),
        },
        "reply": next((e["payload"] for e in result.events if e["kind"] == "AGENT"), ""),
    }
    metrics.update(_score_step(metrics["step_raw"]))
    # A numeric style parameter is only the brain's when the brain produced the plan; on a
    # fallback turn the keyword tables wrote it. Same attribution rule as brain_pass_rate.
    brain_produced = final_source not in ("fallback", "none")
    metrics.update(_score_params(tools, brain_produced))
    metrics.update(_score_expectations(case, metrics, tool_names, executed))
    return metrics


# Arguments the STEP protocol has no slot for. The brain can only emit `tool|effect`
# (plus optional @time / #ordinal suffixes), so any numeric style parameter in the
# final tool call was injected by the hardcoded applyStylePreset table
# (MobileAgentRunner.kt:811), not chosen by the brain. Their presence is the hollow-rescue
# signal from arXiv:2609.23742: structurally valid, semantically not the model's decision.
_PRESET_KEYS = ("saturation", "temperature", "contrast", "brightness", "filter_level")
# Tools whose effect is a style name routed through applyStylePreset (MobileAgentRunner.kt:51).
_STYLE_TOOLS = {"video_filter", "color_tone", "live_photo_filter_effect", "brighten", "portrait_beauty"}

# Mirror of the keyword lists in applyStylePreset (MobileAgentRunner.kt:815-827). A style name
# that matches none of them hits the `else -> return` branch: no parameter is set, and the edit
# silently does nothing the user asked for. That is defect B (planner_reliability_plan.md §3.5).
#
# This list must be kept in step with the Kotlin table. It is mirrored rather than read from the
# device because the resolution happens in-process and leaves no audit trace of its own -- and
# reading it from the final arguments is unreliable, since video_filter injects generic
# contrast/saturation/filter_level defaults whether or not a preset matched.
_PRESET_KEYWORDS = (
    ("黑白", "灰度", "mono", "grayscale", "black and white", "bw"),
    ("冷色", "冷调", "cool", "cold"),
    ("暖色", "暖调", "warm"),
    ("鲜艳", "vivid", "saturate"),
    ("复古", "胶片", "vintage", "film"),
    ("明亮", "提亮", "bright"),
)


def _style_resolves(effect: str) -> bool:
    lowered = effect.lower()
    return any(any(kw in lowered for kw in group) for group in _PRESET_KEYWORDS)


def _score_params(plan_tools: list[dict[str, Any]], brain_produced: bool) -> dict[str, Any]:
    """Second scoring axis: did the brain supply the parameters, or did a hardcoded table?

    PLAN_FINAL is logged from onPlan (MobileAgentRunner.kt:219), which fires *before*
    applyStylePreset, so plan_tools carries the arguments the plan actually shipped with.

    Three outcomes for a style tool:
      - numeric parameters already present -> someone authored them. Who depends on
        brain_produced: on a fallback turn the keyword tables wrote them, on a brain turn
        the model did. Conflating the two is what made rescued turns look brain-authored.
      - a style name the preset table recognises, table supplies every number -> hollow
        (arXiv:2609.23742): structurally valid, semantically not the model's decision
      - a style name the table does NOT recognise -> the requested edit never happens
        (defect B, planner_reliability_plan.md §3.5)

    Classification uses the mirrored keyword table rather than the executed arguments, because
    video_filter and live_photo_filter_effect inject generic contrast/saturation defaults
    whether or not a preset matched. Those defaults hide an unresolved name in the audit while
    still shipping the user an edit they never asked for.
    """
    hollow: list[str] = []
    unresolved: list[str] = []
    authored: list[str] = []
    for call in plan_tools:
        tool = call.get("tool")
        if tool not in _STYLE_TOOLS:
            continue
        planned = call.get("arguments") or {}
        effect = str(planned.get("effect") or "").strip()
        if any(k in planned for k in _PRESET_KEYS):
            authored.append(tool)
            continue
        if not effect:
            continue
        if _style_resolves(effect):
            hollow.append(tool)
        else:
            unresolved.append(tool)
    return {
        "hollow_params": hollow,
        "unresolved_style": unresolved,
        "brain_authored_params": authored if brain_produced else [],
        "table_authored_params": [] if brain_produced else authored,
        "style_tools_planned": sum(1 for c in plan_tools if c.get("tool") in _STYLE_TOOLS),
    }


# The retired six-slot template. Its placeholder names were legal field names, so a 4B model
# could not tell "slot to fill" from "literal value" and echoed them 50% of the time
# (planner_reliability_plan.md §7.7). Counting their reappearance is what makes a regression
# to the old template visible instead of silently re-scored as drift.
_LEGACY_PLACEHOLDERS = ("start_ms", "end_ms", "素材序号", "用户原话证据")
_LEGACY_ECHO = re.compile(r"(start_ms|end_ms|素材序号|用户原话证据|起始毫秒|结束毫秒|#\s*序号)")
_TIME_SUFFIX = re.compile(r"@\s*(\d+)\s*-\s*(\d+)")
_ORDINAL_SUFFIX = re.compile(r"#\s*(\d+)")
_STEP_ROW = re.compile(r"(?im)^\s*STEP\s*=\s*(.*)$")


def _score_step(raw: str) -> dict[str, Any]:
    """Protocol-compliance counters for one STEP-protocol output.

    Scores the two-slot protocol (`STEP=tool|effect`, optional `@start-end` / `#ordinal`
    suffixes on effect) that replaced the six-slot template in P2a, and separately counts
    resurrection of the retired structure so a backslide reads as a regression rather than
    as ordinary drift.
    """
    if not raw.strip():
        return {"has_step_output": False}
    echoed: dict[str, int] = {}
    filled: dict[str, int] = {}
    step_rows: list[list[str]] = []
    legacy_field_rows = 0
    current_shape_rows = 0
    legacy_leak = 0
    for line in raw.splitlines():
        line = line.strip()
        # The retired template is scanned for on every pipe line, not only on STEP= lines.
        # On device the model also emitted it as a malformed header
        # (`ACTION=STEP|video_blur|start_ms|end_ms|素材序号|用户原话证据`, 7 turns), which a
        # STEP-anchored scan would miss -- and that is the loudest possible resurrection signal.
        if "|" in line:
            if len(line.split("|")) >= 6:
                legacy_field_rows += 1
            if _LEGACY_ECHO.search(line):
                legacy_leak += 1
        match = _STEP_ROW.match(line)
        if not match:
            continue
        parts = [p.strip() for p in match.group(1).split("|")]
        step_rows.append(parts)
        # One field is valid too: video_mirror / video_blur / video_reverse take no effect, and
        # counting `STEP=video_mirror` as drift reported D016 as non-clean when it was the
        # cleanest row in the set.
        if len(parts) in (1, 2):
            current_shape_rows += 1
        effect_field = parts[1] if len(parts) > 1 else ""
        # The suffixes are optional and carry their own values; strip them before judging the
        # effect slot so `鲜艳#2` scores as filled, not as an unrecognised string.
        effect = _TIME_SUFFIX.sub("", _ORDINAL_SUFFIX.sub("", effect_field)).strip()
        effect = re.sub(r"^(effect)\s*[=:]", "", effect).strip()
        if effect_field:
            bucket = echoed if effect.lower() == "effect" else filled
            bucket["effect"] = bucket.get("effect", 0) + 1
        # A suffix is filled when it carries digits and echoed when it still names the slot.
        if _TIME_SUFFIX.search(effect_field):
            filled["time_suffix"] = filled.get("time_suffix", 0) + 1
        elif re.search(r"start_ms|end_ms|起始毫秒|结束毫秒", effect_field):
            echoed["time_suffix"] = echoed.get("time_suffix", 0) + 1
        if _ORDINAL_SUFFIX.search(effect_field):
            filled["ordinal_suffix"] = filled.get("ordinal_suffix", 0) + 1
        elif re.search(r"素材序号|#\s*序号", effect_field):
            echoed["ordinal_suffix"] = echoed.get("ordinal_suffix", 0) + 1

    tool_seq = [r[0] for r in step_rows]
    duplicates = sorted({t for t in tool_seq if tool_seq.count(t) > 1})
    has_legacy_header = bool(re.search(r"(?m)^\s*ACTION\s*=", raw))
    return {
        "has_step_output": bool(step_rows),
        "step_rows": len(step_rows),
        "echoed_slots": echoed,
        "filled_slots": filled,
        # Retirement probes, not compliance targets: all three should read 0. Named for what a
        # nonzero value means, because `has_action_header` read as something to maximise.
        "legacy_header": has_legacy_header,
        "legacy_field_rows": legacy_field_rows,
        "legacy_placeholder_leak": legacy_leak,
        "duplicate_tools": duplicates,
        # Rows on the current 1-2 field shape; compared against step_rows to judge cleanliness.
        "current_shape_rows": current_shape_rows,
        "pipe_rows": sum(1 for line in raw.splitlines()
                         if "|" in line and not line.strip().startswith("ACTION")),
        "markdown": "```" in raw or bool(re.search(r"(?m)^#", raw)),
    }


def _score_expectations(case: dict[str, Any], metrics: dict[str, Any],
                        tool_names: list[str], executed: bool) -> dict[str, Any]:
    expect = case.get("expect", {})
    checks: dict[str, Any] = {}

    want = expect.get("outcome")
    if want == "chat":
        checks["outcome_match"] = not tool_names and not executed
    elif want == "plan":
        checks["outcome_match"] = bool(tool_names) or executed
    elif want == "clarify":
        # An ambiguous request must come back asking for specifics rather than silently guessing
        # an operation and editing the user's media on a coin flip.
        #
        # The brain can satisfy this through either channel: QUESTION= (intent="clarify") or a
        # chat reply that asks for more information (intent="chat"). Before P2a only the second
        # was reachable -- the prompt never named QUESTION=, so all 23 ACTION=CLARIFY turns
        # failed parse() and fell through to the keyword tables (defect A). The check stays on
        # observable behaviour (nothing executed, non-empty response) so both channels count,
        # and `clarified` in the metrics is what separates them for attribution.
        responded = bool(metrics.get("clarification")) or bool(metrics.get("reply"))
        checks["outcome_match"] = responded and not tool_names and not executed
    elif want is not None:
        checks["outcome_match"] = None

    must = expect.get("tools_must_include") or []
    checks["tools_must_include"] = [t for t in must if t in tool_names] == list(must) if must else None
    forbid = expect.get("tools_must_not_include") or []
    checks["tools_must_not_include"] = not (set(forbid) & set(tool_names)) if forbid else None

    if expect.get("retrieval") is not None:
        checks["retrieval"] = metrics["retrieved"] == expect["retrieval"]

    # Hard gate: a swallowed edit request is always a failure, whatever else matched.
    checks["not_swallowed"] = not metrics["chat_swallowed_edit"]
    # Without this, a case whose audit rows failed to extract scores all([]) == True
    # and reports a pass for a turn that was never observed.
    checks["turn_recorded"] = bool(metrics.get("events_seen"))

    # Attribution: for a plan/clarify-outcome case, was the outcome the brain's or the tables'?
    # PLAN_FINAL.source == "fallback" means the keyword tables rescued a turn the brain failed to
    # parse (arXiv:2609.23742 hollow rescue). Chat cases have no plan to attribute -> None.
    # This is metadata for brain_pass_rate; it is deliberately NOT folded into `passed`,
    # which keeps the old outcome-only semantics so the two rates stay comparable.
    if want in ("plan", "clarify"):
        checks["brain_attributed"] = metrics.get("final_source") not in ("fallback", "none")
    else:
        checks["brain_attributed"] = None

    # Semantic checks are the ones that actually assert something about the turn's content.
    # not_swallowed / turn_recorded are near-always-true guards; a case whose expect block
    # is empty (or only carries guards) would otherwise pass on all([]) == True and report
    # a green tick for a turn that verified nothing (D015, planner_reliability_plan.md §3.2).
    semantic_keys = ("outcome_match", "tools_must_include", "tools_must_not_include", "retrieval")
    semantic = [checks[k] for k in semantic_keys if checks.get(k) is not None]
    checks["vacuous"] = not semantic

    # `passed` = old outcome semantics, gated so a vacuous case reports None, not True.
    guard_scored = [checks[k] for k in ("not_swallowed", "turn_recorded") if checks.get(k) is not None]
    old_scored = semantic + guard_scored
    checks["passed"] = all(old_scored) if (old_scored and semantic) else None
    # `brain_passed` = passed AND the brain (not the tables) produced the outcome.
    checks["brain_passed"] = (
        checks["passed"] is True and checks["brain_attributed"] is True
    ) if want in ("plan", "clarify") else checks["passed"]
    return {"checks": checks}


def load_cases(path: Path, categories: list[str] | None, limit: int | None) -> list[dict[str, Any]]:
    cases = []
    for line in path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        case = json.loads(line)
        if categories and case.get("category") not in categories:
            continue
        cases.append(case)
    return cases[:limit] if limit else cases


def aggregate(results: list[CaseResult]) -> dict[str, Any]:
    total = len(results)
    errors = [r for r in results if r.error]
    scored = [r for r in results if not r.error]

    def rate(pred) -> dict[str, Any]:
        hit = [r for r in scored if pred(r.metrics)]
        return {"n": len(hit), "total": len(scored), "rate": round(len(hit) / len(scored), 3) if scored else 0}

    echoed: dict[str, int] = {}
    filled: dict[str, int] = {}
    for r in scored:
        for k, v in (r.metrics.get("echoed_slots") or {}).items():
            echoed[k] = echoed.get(k, 0) + v
        for k, v in (r.metrics.get("filled_slots") or {}).items():
            filled[k] = filled.get(k, 0) + v

    # Keys are whatever the current protocol exposes (effect plus the optional suffixes);
    # a fixed tuple silently reported 0.0 for slots that no longer exist.
    slot_keys = sorted(set(echoed) | set(filled))
    slot_echo = {
        k: {
            "echoed": echoed.get(k, 0),
            "filled": filled.get(k, 0),
            "echo_rate": round(echoed.get(k, 0) / max(echoed.get(k, 0) + filled.get(k, 0), 1), 3),
        }
        for k in slot_keys
    }

    by_query: dict[str, list[str]] = {}
    for r in scored:
        by_query.setdefault(r.query, []).append(str(r.metrics.get("planned_tools")))
    variance = {
        q: {"distinct_outcomes": len(set(v)), "runs": len(v), "outcomes": sorted(set(v))}
        for q, v in by_query.items() if len(v) > 1
    }

    passed = [r for r in scored if (r.metrics.get("checks") or {}).get("passed") is True]
    brain_passed = [r for r in scored if (r.metrics.get("checks") or {}).get("brain_passed") is True]
    vacuous = [r for r in scored if (r.metrics.get("checks") or {}).get("vacuous")]
    # Protocol denominators: only turns where the brain actually emitted STEP-protocol text can
    # be judged on field compliance. Dividing by all scored cases (the old behaviour)
    # mixed "brain never ran" into "brain ran and drifted", reporting 5% instead of 17%.
    step_class = [r for r in scored if (r.metrics.get("step_raw") or "").strip()]
    step_parsed = [r for r in step_class if r.metrics.get("has_step_output")]
    # P2a retired both the ACTION header and the six-slot template, so these are now
    # regression probes that should read 0 rather than compliance rates to maximise.
    legacy_header = [r for r in step_parsed if r.metrics.get("legacy_header")]
    legacy_rows = [r for r in step_parsed if r.metrics.get("legacy_field_rows")]
    legacy_leak = [r for r in step_parsed if r.metrics.get("legacy_placeholder_leak")]
    # Clean = every STEP row on the current two-slot shape and no retired placeholder anywhere.
    clean = [r for r in step_parsed
             if not r.metrics.get("legacy_header")
             and not r.metrics.get("legacy_field_rows")
             and not r.metrics.get("legacy_placeholder_leak")
             and r.metrics.get("current_shape_rows") == r.metrics.get("step_rows")]
    clean_ids = {id(r) for r in clean}
    # Latency: the numbers P0 (thinking) will be judged on. Turns where a stage did not run
    # carry None and are excluded rather than counted as zero.
    def latency(stage: str) -> dict[str, Any]:
        vals: list[int] = []
        for r in scored:
            v = (r.metrics.get("latency_ms") or {}).get(stage)
            if isinstance(v, (int, float)):
                vals.append(int(v))
        vals.sort()
        if not vals:
            return {"n": 0}

        def pct(p: float) -> int:
            return vals[min(len(vals) - 1, int(round(p * (len(vals) - 1))))]

        return {"n": len(vals), "p50": pct(0.5), "p95": pct(0.95), "max": vals[-1],
                "mean": round(sum(vals) / len(vals))}

    def reasoning_rate(stage: str) -> dict[str, Any]:
        eligible = [r for r in scored
                    if isinstance((r.metrics.get("latency_ms") or {}).get(stage), (int, float))]
        hit = [r for r in eligible
               if (r.metrics.get("reasoning_chars") or {}).get(stage, 0) > 0]
        return {"n": len(hit), "total": len(eligible),
                "rate": round(len(hit) / len(eligible), 3) if eligible else None}

    # intent_returned_tools is only trustworthy on reports carrying INTENT_BRAIN rows; on
    # legacy data the intent sample is chat-fast-path-only (planner_reliability_plan.md §3.4).
    intent_complete = [r for r in scored if r.metrics.get("intent_trace_complete")]
    intent_tools = [r for r in intent_complete if r.metrics.get("intent_returned_tools")]

    return {
        "cases_run": total,
        "errors": len(errors),
        "scored": len(scored),
        "pass_rate": round(len(passed) / len(scored), 3) if scored else 0,
        # Primary metric: passes the brain actually earned. pass_rate - brain_pass_rate is the
        # share of green ticks the keyword tables paid for (hollow rescue).
        "brain_pass_rate": round(len(brain_passed) / len(scored), 3) if scored else 0,
        "rescued_by_tables": len(passed) - len(brain_passed),
        "vacuous": len(vacuous),
        "intent_called": rate(lambda m: m.get("intent_called")),
        "intent_trace_complete": {"n": len(intent_complete), "total": len(scored)},
        "intent_returned_tools": {"n": len(intent_tools), "total": len(intent_complete),
                                  "rate": round(len(intent_tools) / len(intent_complete), 3)
                                  if intent_complete else None},
        "chat_swallowed_edit": rate(lambda m: m.get("chat_swallowed_edit")),
        "keyword_fallback": rate(lambda m: m.get("keyword_fallback")),
        "retrieved": rate(lambda m: m.get("retrieved")),
        "executed": rate(lambda m: m.get("executed")),
        # Protocol compliance, on the turns where the brain emitted STEP text at all.
        # The legacy_* counters are regression probes for the retired six-slot template and
        # should read 0; a nonzero value means the model resurrected the old structure.
        "protocol": {
            "step_class_turns": len(step_class),
            "parsed_step_rows": len(step_parsed),
            "protocol_clean_rate": round(len(clean) / len(step_parsed), 3) if step_parsed else None,
            "legacy_header_rate": round(len(legacy_header) / len(step_parsed), 3) if step_parsed else None,
            "legacy_field_rows": len(legacy_rows),
            "legacy_placeholder_leak": len(legacy_leak),
            # Judged over turns that emitted STEP rows: QUESTION=/REPLY= turns are their own
            # legitimate class now, not protocol drift, so they must not land in this list.
            "drifted": [r.case_id for r in step_parsed if id(r) not in clean_ids],
        },
        "has_duplicate_tools": rate(lambda m: bool(m.get("duplicate_tools"))),
        "markdown_pollution": rate(lambda m: m.get("markdown")),
        # Parameter-quality axis: hollow = preset table supplied the numbers, unresolved = the
        # style name matched no table entry so the edit silently did nothing.
        "param_quality": {
            "style_tools_planned": sum(r.metrics.get("style_tools_planned") or 0 for r in scored),
            "hollow": {r.case_id: r.metrics["hollow_params"] for r in scored
                       if r.metrics.get("hollow_params")},
            "unresolved": {r.case_id: r.metrics["unresolved_style"] for r in scored
                           if r.metrics.get("unresolved_style")},
            "brain_authored": {r.case_id: r.metrics["brain_authored_params"] for r in scored
                               if r.metrics.get("brain_authored_params")},
            "table_authored": {r.case_id: r.metrics["table_authored_params"] for r in scored
                               if r.metrics.get("table_authored_params")},
        },
        "latency_ms": {
            stage: latency(stage) for stage in (
                "turn", "intent", "plan", "intent_reasoning", "intent_format",
                "plan_reasoning", "plan_format"
            )
        },
        "planner_strategies": sorted({
            str(r.metrics.get("planner_strategy")) for r in scored
            if r.metrics.get("planner_strategy")
        }),
        "reasoning_present": {
            "intent": reasoning_rate("intent"),
            "plan": reasoning_rate("plan"),
        },
        "slot_echo": slot_echo,
        "sampler_variance": variance,
    }


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--repeat", type=int, default=1, help="runs per case, for sampler variance")
    ap.add_argument("--category", action="append", help="restrict to categories (repeatable)")
    ap.add_argument("--max-cases", type=int)
    ap.add_argument("--timeout", type=int, default=240, help="seconds to wait per case")
    ap.add_argument("--same-session", action="store_true",
                    help="reuse one session; default opens a fresh session per case")
    ap.add_argument("--eval-set", type=Path, default=EVAL_SET)
    ap.add_argument("--report", type=Path, default=REPORT,
                    help="report JSON path (default: device_eval_report.json)")
    ap.add_argument("--failures", type=Path, default=FAILURES,
                    help="failure JSONL path (default: device_eval_failures.jsonl)")
    args = ap.parse_args()

    if not sh(["getprop", "ro.build.version.sdk"]).strip():
        print("没有 adb 设备", file=sys.stderr)
        return 1
    if sh(["settings", "get", "secure", "default_input_method"]).strip() != IME:
        print(f"当前输入法不是 {IME}，中文无法输入。先执行：\n"
              f"  adb shell ime enable {IME} && adb shell ime set {IME}", file=sys.stderr)
        return 1

    cases = load_cases(args.eval_set, args.category, args.max_cases)
    if not cases:
        print("没有匹配的 case", file=sys.stderr)
        return 1
    print(f"case 数={len(cases)} repeat={args.repeat} timeout={args.timeout}s "
          f"会话={'复用' if args.same_session else '每条新建'}")

    ensure_awake()
    ensure_foreground()
    results: list[CaseResult] = []
    consecutive_session_failures = 0
    for case in cases:
        for rep in range(args.repeat):
            label = f"{case['case_id']}({case['user_text']})" + (f" #{rep}" if args.repeat > 1 else "")
            print(f"\n▶ {label}", flush=True)
            r = run_case(case, rep, args.timeout, fresh_session=not args.same_session)
            results.append(r)
            m = r.metrics
            if r.error:
                print(f"  ✗ {r.error}")
                # A session that cannot be created is not a result, it is a broken rig. Three in a
                # row aborts the run instead of writing 60 error rows that look like a
                # catastrophic regression; the report would otherwise be indistinguishable from
                # one where the brain genuinely failed everything.
                if "无法新建会话" in str(r.error):
                    consecutive_session_failures += 1
                    if consecutive_session_failures >= 3:
                        print("\n连续 3 次无法新建会话：设备状态异常（休眠/焦点丢失/IME），中止本次评测。\n"
                              f"先执行 adb shell input keyevent KEYCODE_WAKEUP 确认屏幕点亮后重跑。",
                              file=sys.stderr)
                        return 2
                else:
                    consecutive_session_failures = 0
                continue
            consecutive_session_failures = 0
            print(f"  tools={m.get('planned_tools')} executed={m.get('executed')} "
                  f"swallowed={m.get('chat_swallowed_edit')} fallback={m.get('keyword_fallback')}")
            if m.get("step_raw"):
                print(f"  step={m['step_raw'][:110]!r}")
            checks = m.get("checks") or {}
            extra = []
            if m.get("hollow_params"):
                extra.append(f"hollow_params={m['hollow_params']}")
            if m.get("unresolved_style"):
                extra.append(f"unresolved_style={m['unresolved_style']}")
            if checks.get("vacuous"):
                extra.append("VACUOUS")
            print(f"  passed={checks.get('passed')} brain_passed={checks.get('brain_passed')}"
                  + ("  " + " ".join(extra) if extra else ""))

    summary = aggregate(results)
    args.report.parent.mkdir(parents=True, exist_ok=True)
    args.failures.parent.mkdir(parents=True, exist_ok=True)
    args.report.write_text(json.dumps(
        {"summary": summary,
         "results": [{"case_id": r.case_id, "query": r.query, "category": r.category,
                      "repeat": r.repeat, "error": r.error, "timed_out": r.timed_out,
                      "metrics": r.metrics} for r in results]},
        ensure_ascii=False, indent=2), encoding="utf-8")
    with args.failures.open("w", encoding="utf-8") as fh:
        for r in results:
            checks = r.metrics.get("checks") or {}
            # A vacuous case (passed=None) asserts nothing and must be surfaced, not silently
            # dropped; a plan case the tables had to rescue is a brain failure worth reviewing
            # even when the old metric called it a pass.
            worth_logging = (
                r.error
                or checks.get("passed") is False
                or checks.get("vacuous")
                or (checks.get("passed") is True and checks.get("brain_passed") is False)
            )
            if worth_logging:
                fh.write(json.dumps({"case_id": r.case_id, "query": r.query, "error": r.error,
                                     "metrics": r.metrics}, ensure_ascii=False) + "\n")

    print("\n" + "=" * 62)
    print(json.dumps(summary, ensure_ascii=False, indent=2))
    print(f"\n报告: {args.report}\n失败明细: {args.failures}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
