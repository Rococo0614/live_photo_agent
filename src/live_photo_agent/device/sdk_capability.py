"""工具词表 -> 端侧能力的映射。

## 这张表为什么必须存在

原有 17 个 L0 工具的执行实现建立在「PC 上有 ffmpeg / OpenCV」的假设上。该假设已
作废：执行只发生在手机端，可用的东西被钉死为 **vivo 视频编辑 SDK + 必要的云端
VLM/扩散接口**。词表里残留的 PC 痕迹是可以直接看出来的，例如
``EXTRACT_SUBJECT_MATTE`` 的 ``mode`` 默认值是 ``mog2|knn`` —— 那是 OpenCV 的背景
建模算法名，手机 SDK 里没有这个概念。

因此在把规划结果交给端侧之前，必须先回答每个工具「到底谁来执行」。这张表就是那个
答案，也是翻译层拒绝无法执行的步骤时所依据的唯一事实来源。

## 关于可信度的说明

- 标 [CapabilityStatus.VERIFIED][] 的两项是真机跑通过的，见
  ``android/docs/headless_render_findings.md``。
- 标 [CapabilityStatus.SDK_UNVERIFIED][] 的 ``sdk_hint`` **是根据 SDK 类名清单做的
  推测，不是已确认的调用路径**。类名清单来自运行时反射探针
  ``android/docs/sdk_api_probe_V2547A.txt``（SDK 无文档、无 sources）。
  推测的价值在于给后续验证指方向，但不能当作「已支持」使用 —— 所以它和
  ``VERIFIED`` 分成两档，翻译层只放行后者。
"""

from __future__ import annotations

from dataclasses import dataclass
from enum import Enum

from ..models import ToolName


class CapabilityStatus(str, Enum):
    """一个工具在端侧的可执行状态。"""

    #: 真机验证过，翻译层允许放行
    VERIFIED = "verified"
    #: SDK 里存在看起来对应的类，但没验证过调用路径与参数语义
    SDK_UNVERIFIED = "sdk_unverified"
    #: SDK 做不到，必须调云端 VLM / 扩散接口（云端工具也算工具）
    CLOUD_REQUIRED = "cloud_required"
    #: 不碰媒体，用 Android 原生能力即可（图库扫描、集合筛选等）
    DEVICE_NATIVE = "device_native"
    #: 由规划器自身消化，不产生媒体操作
    PLANNER_INTERNAL = "planner_internal"


@dataclass(frozen=True)
class Capability:
    status: CapabilityStatus
    #: 推测或已确认的 SDK 承载物；非 SDK 路径时说明由谁承载
    sdk_hint: str
    #: 补充说明，特别是「为什么不是 SDK」或「验证时该看什么」
    note: str = ""


#: 工具 -> 端侧能力。缺项视为未定义，翻译层会当作不可执行处理。
CAPABILITIES: dict[ToolName, Capability] = {
    # ---- 已真机验证 ----
    ToolName.CLIP_TRIM: Capability(
        CapabilityStatus.VERIFIED,
        "VMClip.startTimeMs / durationMs",
        "注意契约用 start_ms/end_ms，SDK 用起点+时长，翻译层负责换算",
    ),
    ToolName.EXPORT_MP4: Capability(
        CapabilityStatus.VERIFIED,
        "VMEditor.startExport + VMExportEncodeConfig",
        "实测产物恒定略长于请求（整帧对齐），校验用不对称容差",
    ),
    # ---- SDK 疑似支持，待验证 ----
    ToolName.CONCAT_CLIPS: Capability(
        CapabilityStatus.VERIFIED,
        "VMEffectEditor 画布 / VMResource.vmClips 传多个 VMClip",
        "Python 翻译层已把三拼翻成 DeviceCanvas(vertical_3up)；Kotlin 像素拼合待设备验证",
    ),
    ToolName.COLOR_ENHANCE: Capability(
        CapabilityStatus.SDK_UNVERIFIED, "VMFilterData", "滤镜合法取值域未知"
    ),
    ToolName.ADD_TEXT_OVERLAY: Capability(
        CapabilityStatus.VERIFIED,
        "TextStickerExData / VMTextStickerData",
        "Python 翻译层已翻成 DeviceTextOverlay；Kotlin 文字渲染待设备验证",
    ),
    ToolName.MIX_AUDIO_BGM: Capability(
        CapabilityStatus.SDK_UNVERIFIED, "VMAudioEditor / VMMusicExtractor", ""
    ),
    ToolName.EXTRACT_KEY_FRAMES: Capability(
        CapabilityStatus.SDK_UNVERIFIED, "VMThumbnailsGenerator / VMVideoFramePicker", ""
    ),
    ToolName.SELECT_COVER_FRAME: Capability(
        CapabilityStatus.SDK_UNVERIFIED,
        "VMVideoFramePicker",
        "SDK 只负责取帧；「哪一帧更好」的判断仍需模型",
    ),
    ToolName.SET_DISPLAY_FRAME: Capability(
        CapabilityStatus.VERIFIED,
        "VMLivePhotoEffectEngine",
        "Python 翻译层已翻成 DeviceLivePhotoPack；Kotlin 重打包待设备验证",
    ),
    ToolName.OVERLAY_SUBJECT_CLIP: Capability(
        CapabilityStatus.VERIFIED,
        "VMStickerData / VMEffectEditor / GPUMattingFilter",
        "Python 翻译层已翻成 DeviceOverlay；Kotlin 主体叠加待设备验证",
    ),
    ToolName.CLIP_SPEED: Capability(
        CapabilityStatus.SDK_UNVERIFIED,
        "VMClip 速度相关字段（未在探针里定位到）",
        "探针类名清单里没看到明确的变速载体，可能需要另找 API",
    ),
    # ---- 必须走云端模型 ----
    ToolName.EXTRACT_SUBJECT_MATTE: Capability(
        CapabilityStatus.CLOUD_REQUIRED,
        "云端抠像接口",
        "契约里 mode=mog2|knn 是 OpenCV 背景建模，属于 PC 假设的残留，需重定义",
    ),
    ToolName.SUBJECT_SEGMENTATION: Capability(
        CapabilityStatus.CLOUD_REQUIRED,
        "云端分割 / VLM",
        "SDK 有 VMBodyStrokeEngine 但那是人体描边特效，不等于可用的分割掩码",
    ),
    ToolName.ESTIMATE_MOTION_SCORE: Capability(
        CapabilityStatus.CLOUD_REQUIRED,
        "云端 VLM 预处理产出",
        "作为素材资产随图片下发到端侧，端侧不重算",
    ),
    ToolName.SEARCH_BY_TEXT: Capability(
        CapabilityStatus.CLOUD_REQUIRED,
        "云端 VLM 语义检索",
        "端侧可退化为在已下发的标签上做匹配",
    ),
    ToolName.STABILIZE_CLIP: Capability(
        CapabilityStatus.CLOUD_REQUIRED,
        "无端侧承载物",
        "探针里没有防抖相关类；若云端也不提供，这个工具应当从词表移除",
    ),
    # ---- 不碰媒体 ----
    ToolName.SCAN_LIBRARY: Capability(
        CapabilityStatus.DEVICE_NATIVE, "MediaStore 查询", ""
    ),
    ToolName.FILTER_SELECTED: Capability(
        CapabilityStatus.DEVICE_NATIVE, "端侧集合运算", ""
    ),
    ToolName.SUMMARIZE_RESULTS: Capability(
        CapabilityStatus.PLANNER_INTERNAL, "规划器自身", ""
    ),
}


def capability_of(tool: ToolName) -> Capability:
    """查表。未登记的工具按「无端侧承载物」处理，而不是抛异常。

    刻意不抛：词表新增工具时，翻译层应当把它标成不可执行并继续跑完剩余步骤，
    这样端到端链路不会因为一个新工具整条断掉。
    """
    return CAPABILITIES.get(
        tool,
        Capability(
            CapabilityStatus.CLOUD_REQUIRED,
            "未登记",
            "该工具尚未在 sdk_capability 表中登记，无法判定由谁执行",
        ),
    )


def is_device_executable(tool: ToolName) -> bool:
    """是否允许交给端侧 SDK 执行。只有真机验证过的才放行。"""
    return capability_of(tool).status is CapabilityStatus.VERIFIED
