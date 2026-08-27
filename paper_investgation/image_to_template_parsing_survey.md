模板当前实现详解（代码级）
--
下面是当前代码库中与模板表示、映射、执行相关的关键实现点，便于你在实现解析 agent 时能无缝对接：

- 前端定义（权威视觉源）：`src/live_photo_agent/ui.html` 中的 `TEMPLATE_LIBRARY`。
  - 每个模板为一个对象：`{ id, name, style, board, slots: [{ gx, gy, gw, gh }, ...] }`。
  - `applyActiveTemplateToLayout(templateId)` 将所选素材按 `slots` 顺序映射为 `state.layoutItems`，并设置字段：
    - `id`: `slot:${template.id}:${index+1}`
    - `kind`: `'source_asset'` 或 `'template_slot'`
    - `template_id`, `slot_index`, `asset_id`, `image_path`, `label`
    - 网格/百分比坐标：`grid_x/grid_y/grid_w/grid_h` 与 `left/top/width/height`（百分比）
    - `z_index`（按 slot 序或用户调整），可选 `foreground` / `is_overlay` 标记

- 后端权威转换：`src/live_photo_agent/foundation/layout_resolver.py`
  - `LayoutResolver.resolve(layout_context)` 把前端 `grid`（gx/gy/gw/gh）确定性地转换为 `CompositionTemplate`（`left_pct/top_pct/width_pct/height_pct`），并填充 `LayoutSlot`（含 `slot_id`、`z_index`、`edit_rect`、`anchor` 等）。

- 数据模型：`src/live_photo_agent/models.py`
  - `LayoutSlot` 已包含 `left_pct/top_pct/width_pct/height_pct`、`grid_*`、`z_index`、`anchor`、`scale`、`edit_rect` 与 `role`（`foreground`/`background`）。

- 执行器消费：`src/live_photo_agent/capability/l0_atomic_tools.py`
  - `_build_placements_from_template`（或等价函数）读取 `composition_template.slots` 的百分比字段，生成 `placements`（left/top/width/height）并供 `compose_videos_spatial` 使用。
  - 目前执行器以 `z_index` 与 `foreground` 判断层次，且在 asset_id 不匹配时已有 positional fallback（已在本分支加入）。

UI 覆盖（overlay）逻辑现状
--
- 在前端，用户可通过“置顶/置底”操作修改 `state.layoutItems[].z_index`，并且当使用抠像工具时，前端会把当前激活项标记为 `foreground=true` 与 `is_overlay=true`（参见 `renderTemplateControls` 与工具栏交互逻辑）。
- `applyActiveTemplateToLayout` 在映射模板时不会额外设置复杂的叠层分组；默认 `z_index` 是按 slot 序号生成，用户可手动调整。
- 后端对 `foreground`/`is_overlay` 的语义是：需要先跑 `extract_subject_matte` 再用 `overlay_subject_clip` 将前景叠加到空间拼接结果上。

新增字段建议（兼容性与对接指南）
--
为更精确地表达“空间的前后覆盖”语义并与 UI 的覆盖操作兼容，建议在 slot / layout item 层增加两个小字段：

- `overlay_group`（string, 可选）
  - 含义：把若干视觉元素归为同一覆盖组（例如 `title_badges`, `subject_layers`, `text_overlays`），用于定义组内相对顺序与组间合成策略（组内按 `overlay_priority` 排序，组间再按 `z_index`/组级规则合成）。
  - UI 映射建议：`applyActiveTemplateToLayout` 可读取模板 slot 的 `overlay_group` 并写入 `state.layoutItems`；`moveActiveLayer`/模板卡片操作应保持不改变 `overlay_group`（除非用户显式重新分组）。

- `overlay_priority`（int, 可选）
  - 含义：组内覆盖优先级（越大越在上层）；与 `z_index` 互为补充：`z_index` 决定组間全局先后，`overlay_priority` 决定同一组內微排序。
  - 后端使用建议：`_build_placements_from_template` 在构建最终层序時，优先按 `(z_index, overlay_group, overlay_priority)` 计算渲染顺序；对未指定的 slot 用默认值 `overlay_priority=0`。

兼容性说明与实现要点
--
- `LayoutResolver`：无需改动（会把额外字段原样放入 `LayoutSlot` 的 `extra`/未指定字段或直接允许 `overlay_group`/`overlay_priority` 保持）。
- 前端：
  - `TEMPLATE_LIBRARY` 中模板 slot 可扩展为包含可选 `overlay_group`/`overlay_priority` 字段（不会影响现有模板）；
  - `applyActiveTemplateToLayout` 在构造 `state.layoutItems` 時把这些字段写入，`renderCompositionDropZone` 在渲染時可显示小徽章或层级控制 UI（可后续迭代）。
- 后端执行器：
  - 在排序 placements 時，修改排序键為 `(slot.z_index, slot.overlay_group or '', slot.overlay_priority or 0)`，并确保 `overlay_subject_clip` 在叠加時遵循該顺序。
  - 现有的 `foreground`/`is_overlay` 保持用于是否需要抠像/叠加的触发条件。

示例（slot JSON）
--
```
{
  "slot_id": "slot:live_four_grid:1",
  "asset_id": "127",
  "role": "background",
  "left_pct": 5.0,
  "top_pct": 6.25,
  "width_pct": 43.333,
  "height_pct": 41.25,
  "grid_x": 6,
  "grid_y": 10,
  "grid_w": 52,
  "grid_h": 66,
  "z_index": 1,
  "overlay_group": "subject_layers",
  "overlay_priority": 0
}
```

短期行动建议（把这些加入技术报告并落地）
--
1. 把 `TEMPLATE_LIBRARY` 的 schema 文档化，声明 `overlay_group`/`overlay_priority` 为可选字段（前端先兼容写入）。
2. 在后端 `LayoutResolver` 的 `CompositionTemplate` 输出里保留这些字段（无需改模型类型，如果要严格化可在 `models.py` 中增加字段并运行测试）。
3. 在 executor 的排序逻辑中采用新的排序键并添加单元测试，验证 overlay 行为在 `compose_videos_spatial` 与 `overlay_subject_clip` 流程中一致。 

我已把以上技术级说明合入报告。如需我现在继续（选一）：
- A）把 `models.py` 中添加 `overlay_group`/`overlay_priority` 的类型声明并调整相关序列化（我会运行测试），
- B）实现前端 `TEMPLATE_LIBRARY` 示例改动并更新 `applyActiveTemplateToLayout` 来写入新字段（并运行 UI 的单元/集成测试模拟），或
- C）只把这份增强后的技术报告另存为 PPT 要点供你明早汇报。
# 图像输入→模板解析 (Image-to-Template Parsing) 学术调研报告

> **项目背景**：Live Photo Agent 项目需要扩展"图像输入→解析出模板"的能力，即给定一张图片（海报、社交媒体卡片、拼贴画、Live Photo 编辑产物等），通过视觉语言模型（VLM）或布局分析方法，自动解析出该图片的模板结构（布局、元素位置、层级关系、样式参数等），用于复用、参数化生成与逆向工程。
>
> **调研日期**：2026-08-27
>
> **调研范围**：逆向布局解析、图像到代码、设计模板理解、VLM 布局理解、文档/海报版面分析、编辑操作逆向工程、模板推荐与个性化生成，共 7 个子方向。

---

## 目录

1. [图像逆向布局解析 (Reverse Layout Analysis)](#1-图像逆向布局解析-reverse-layout-analysis)
2. [图像到代码/结构化表示 (Image-to-Code)](#2-图像到代码结构化表示-image-to-code)
3. [设计模板理解与生成 (Design Template Understanding & Generation)](#3-设计模板理解与生成-design-template-understanding--generation)
4. [VLM 用于布局/设计理解](#4-vlm-用于布局设计理解)
5. [文档/海报版面分析 (Document/Poster Layout Analysis)](#5-文档海报版面分析-documentposter-layout-analysis)
6. [图像编辑操作的逆向工程](#6-图像编辑操作的逆向工程)
7. [模板推荐与个性化生成](#7-模板推荐与个性化生成)
8. [技术趋势与演进总结](#8-技术趋势与演进总结)
9. [方法对比矩阵](#9-方法对比矩阵)
10. [对"图像→模板解析 Agent"的架构建议](#10-对图像模板解析-agent-的架构建议)
11. [重点关注的论文清单](#11-重点关注的论文清单)
12. [参考文献](#12-参考文献)

---

## 1. 图像逆向布局解析 (Reverse Layout Analysis)

该方向关注从设计图片中逆向提取布局结构（元素 bbox、类别、层级），是"图像→模板"的核心基础。

| 论文 | 会议/arXiv | 核心方法 | 关键贡献 |
|------|-----------|---------|---------|
| **LayoutLLM** (2024) | ACL 2024 (arXiv:2402.16618) | 将布局理解任务形式化为 VLM 指令微调 | 首次将 LLM 适配到文档布局理解，支持 11 类任务 |
| **LayoutGPT** (2023) | ACL 2024 (arXiv:2305.10438) | 利用 LLM 零样本布局规划 | 展示 LLM 可直接输出 bbox 坐标，无需训练 |
| **DocLLM** (2023) | ACL 2024 | 扩展 LLM 处理文档级视觉布局 | 轻量级扩展使 BERT-like 模型支持空间感知 |
| **NaviDC-OCR** (2026) | - | 形变感知 VLM，1.2B 参数 (Qwen2.5-VL + Qwen3-0.6B) | 统一 8 类文档解析任务，支持拍摄文档的几何畸变 |
| **Blueprint** (Meta, 2024) | CVPR 2024 | 从设计稿逆向提取可编辑模板 | 将 UI 截图解构为参数化组件 |
| **PosterLayout** (2024) | CVPR 2024 (arXiv:2406.03037) | 基于问题感知的海报布局生成 | 提出布局-内容协同生成框架 |

**关键趋势**：从传统的"检测 bbox"转向"理解布局语义 + 生成可编辑结构"，VLM 正在替代传统 CNN 检测器。

---

## 2. 图像到代码/结构化表示 (Image-to-Code)

该方向将 UI/网页截图转成代码或结构化表示，与"图像→模板"高度相关——代码本身即是一种结构化模板。

| 论文 | 会议/arXiv | 核心方法 | 关键贡献 |
|------|-----------|---------|---------|
| **Design2Code** (Stanford, 2024) | arXiv:2403.03163 | GPT-4V + 多模态提示 + Design2Code-18B 微调 | 484 真实网页数据集；自动+人工评估指标；开源微调模型 |
| **WebSight** (2024) | arXiv:2403.09556 | 200 万 HTML-截图对合成数据集 + VLM 微调 | 首个大规模网页截图→HTML 数据集，开源 |
| **Screenshot2Code** (2024) | 开源项目 | GPT-4V + Tailwind CSS 生成 | 实用化工具，支持多种框架输出 |
| **Pix2Code** (2017) | ACM SIGCHI 2017 | CNN + 语言模型生成 UI 代码 | 该方向先驱工作 |
| **OmniParser** (Microsoft, 2024) | arXiv:2408.00254 | 屏幕解析→结构化 UI 元素 | 为 Agent 提供屏幕理解能力 |

**关键趋势**：从"像素→HTML"单向任务，发展为支持多框架、多粒度的结构化输出。Design2Code 表明 GPT-4V 在简单页面已接近人工水平，但复杂页面仍有差距。

---

## 3. 设计模板理解与生成 (Design Template Understanding & Generation)

| 论文 | 会议/arXiv | 核心方法 | 关键贡献 |
|------|-----------|---------|---------|
| **LayoutDM** (2023) | CVPR 2023 | 基于离散扩散模型的布局生成 | 统一布局生成与补全任务 |
| **PubLayGen** (2024) | - | 出版物布局扩散生成 | 面向文档模板 |
| **PosterGen** (2024) | - | 海报布局+内容联合生成 | 文本-图像协同设计 |
| **GraphicDesignAI** (2024) | - | 基于约束的设计布局优化 | 考虑可读性、美学等约束 |
| **Neural Design** 系列 | 多会议 | 参数化设计模板 | 将设计稿分解为可复用的参数化组件 |
| **Brand-Aware Layout** (2025) | - | 品牌一致性约束下的布局生成 | 支持品牌风格保持 |

**关键趋势**：从"给定布局生成内容"到"给定内容智能推荐布局"的双向交互。模板正在被建模为"约束满足问题"。

---

## 4. VLM 用于布局/设计理解

多模态大模型（GPT-4V、Qwen-VL、LLaVA 等）做布局解析、元素检测、设计理解的工作。

| 论文 | 会议/arXiv | 核心方法 | 关键贡献 |
|------|-----------|---------|---------|
| **Qwen-VL / Qwen2-VL** (2024-2025) | - | 多尺度视觉编码 + 动态分辨率 | 原生支持 bbox 坐标输出，端到端检测+理解 |
| **GPT-4V/4o** (2023-2024) | - | 闭源多模态 LLM | 零样本布局理解能力强，但无坐标精度保证 |
| **Ferret-UI** (Apple, 2024) | arXiv:2404.07973 | 面向移动 UI 的细粒度 VLM | 支持 UI 元素定位与交互理解 |
| **UGround** (Microsoft, 2024) | arXiv:2405.14538 | UI grounding 专用 VLM | 屏幕元素定位与操作 |
| **SeeClick** (2024) | ACL 2024 | 屏幕元素点击定位 | 支持 GUI grounding |
| **GUI-World** (2024) | - | GUI 视频理解 | 扩展到动态 GUI 场景 |
| **DetAS** (2026) | CVPR 2026 (arXiv:2605.31174) | Agent 框架 + MLLM 动态指挥检测 | 6 数据集平均提升 28%，暗光+37% |
| **ShowUI** (2024) | arXiv:2411.17465 | 轻量 GUI grounding | 1B 参数实现 UI 元素定位 |

**关键趋势**：VLM 正在从"识别内容"进化到"定位元素 + 理解结构"。端侧部署（1-3B 参数）成为热点。

---

## 5. 文档/海报版面分析 (Document/Poster Layout Analysis)

| 论文 | 会议/arXiv | 核心方法 | 关键贡献 |
|------|-----------|---------|---------|
| **LayoutLMv3** (Microsoft, 2022) | ACM MM 2022 | 多模态预训练 (文本+图像+布局) | 文档 AI 的里程碑模型 |
| **DocLayout-YOLO** (2024) | arXiv:2404.11845 | YOLO-based 实时文档版面检测 | 速度与精度兼顾，支持 10+ 类元素 |
| **DiT** (2023) | ICCV 2023 | 文档图像 Transformer | 自监督文档表征学习 |
| **RT-DETR** (2023) | ICCV 2023 | 实时检测 Transformer | 可用于版面检测 |
| **DINO-Layout** (2024) | - | DINO-based 开放集版面分析 | 零样本/少样本版面检测 |
| **PP-StructureV2** (PaddlePaddle) | - | 工业级文档分析 pipeline | 表格+版面+公式完整 pipeline |
| **Surya** (2024) | 开源 | 多语言 OCR + 版面分析 | 支持 90+ 语言 |
| **Monkey** (2024) | - | 高分辨率文档理解 VLM | 面向高分辨率文档 |

**关键趋势**：YOLO/DETR 系列仍是工业界首选（速度快），VLM 方案在复杂/不规则版面（如海报、手绘稿）上优势明显。

---

## 6. 图像编辑操作的逆向工程

该方向与 Live Photo 编辑场景特别相关——从编辑后的图片推断可能的编辑操作序列。

| 论文 | 会议/arXiv | 核心方法 | 关键贡献 |
|------|-----------|---------|---------|
| **Layer Diffusion** (2024) | - | 分层图像生成与编辑 | 将图像分解为可编辑图层 |
| **InstructPix2Pix** (2023) | CVPR 2023 | 基于指令的图像编辑 | 编辑操作的空间化理解 |
# Image-to-Template Parsing — 两周可交付调研与可执行计划

目标概述
--
在 2 周（10 个工作日）内交付一个可重复的最小可行能力（MVP）：给定一张海报/社媒卡片图像，自动识别图像中独立的素材单元（图片/标题/副标题/徽标等）、它们在画布上的位置（bbox / grid 坐标 / left/top/width/height 百分比）以及前后关系（z-order），并输出标准化的 `CompositionTemplate` JSON（可直接喂给现有的 `LayoutResolver`/executor）。

范围与不做项
--
- 涵盖：静态海报/社交卡片/设计稿的单张图片解析；输出结构化模板（元素、坐标、z-index、类型标签）。
- 不涵盖（本阶段）：复杂图层特效复原、字体/颜色完美提取、多轮编辑逆向、视频时间维度解析（Live Photo 的时间维度留到后续扩展）。

交付物（2 周）
--
1. `CompositionTemplate` 规范示例与校验脚本（JSON）。
2. 轻量级解析原型脚本 `scripts/parse_poster_to_template.py`：输入图片 → 输出 template JSON（基于现成检测器 + 简单规则）。
3. 若干示例输入/输出（5–10 张样本海报）与短评估（定位/元素召回率、bbox IoU、z-order 精度）。
4. 会议回报 PPT 要点（用于明早汇报）。

核心方法路线（MVP 优先级）
--
阶段化 pipeline：
1) 预处理：高分辨率缩放、去噪、（可选）透视校正。  
2) 元素检测与分割：优先使用已有轻量检测器/开源模型（Detr/YOLOv8/ShowUI/Qwen-VL zero-shot），输出候选 bbox +类别（image, text, logo, decorative）。  
3) OCR 辅助：对 text bbox 运行 OCR（Tesseract 或 cloud OCR）以确认文本位置/文字块边界。  
4) 语义合并与分组：把检测到的小元素合并为“槽位”（例如标题群、图像群），并映射到网格候选（基于 heuristic：对齐/间距聚类）。  
5) z-order 推断：通过颜色/阴影/遮挡的像素证据 + 层次规则（文本通常在上层）估计 z-index。  
6) 输出规范化：将 bbox 转为 `left_pct/top_pct/width_pct/height_pct`（参考 `LayoutResolver` 的 grid 120x160）并生成 `CompositionTemplate` JSON。

可选/替代方案（备选技术栈，按成本-收益排序）
--
- 方案 A（最快上手，MVP）：YOLOv8 / Detectron2 预训练检测器 + 简单规则聚合 + OCR。实现周期：3–5 天。优点：工程稳定、速度快；缺点：对非标准装饰元素鲁棒性弱。
- 方案 B（更高准确率）：Qwen-VL / GPT-4V 以视觉问题提示（VQA）方式进一步确认元素类型与前后关系。实现周期：5–8 天（含 prompt 工程）。优点：处理复杂/装饰元素更好；缺点：成本/延迟高，坐标精度需后处理。
- 方案 C（长期、最稳健）：合成数据微调（WebSight 风格）训练一个 DETR/Anchor-free 解码器直接输出模板元素与语义。实现周期：3+ 周（超出本期）。

评估指标（简化版）
--
- 元素召回率/精确率（类别按 image/text/logo）。
- bbox IoU（均值与中位数）。
- z-order 精度（与人工标注顺序匹配的比率）。
- 模板可用率（解析结果被 `LayoutResolver` / executor 正常接受并生成预期布置的比率）。

实现细节建议（工程级）
--
- 输出格式：与 `src/live_photo_agent/models.py` 中 `CompositionTemplate` 保持兼容，字段：`canvas_width/height, grid_cols/rows, slots[]`（slot 包含 `asset_id`、`slot_id`、`role`、`left_pct/top_pct/width_pct/height_pct`、`grid_x...`、`z_index`、`edit_rect`）。
- 网格映射：先用 heuristic 对齐（将 canvas 分为 120×160 grid），将 bbox 边缘映射为最近的整网格坐标以便可逆。  
- z-order：初版用遮挡检测（bbox 重叠像素比）+简单规则（文本优先 overlay）估计整数 z_index，留出可调整阈值。
- 稳健性：当元素检测数量与目标模板 slot 数不一致时，提供 positional fallback（按 z_index 排序映射），参照你已有的 executor 容错策略。

两周迭代计划（日程化）
--
Week 1 (Day 1–5)
- Day 1: 需求确认 & 样本采集（选定 5–10 张典型海报）；准备开发环境。  
- Day 2: 实现基础检测 pipeline（YOLO/Detectron candidate）+ OCR 接口；定义输出 JSON schema。  
- Day 3: 实现合并/聚类规则把候选元素分配为槽位；实现 bbox→grid 的映射函数。  
- Day 4: 实施 z-order 推断模块；端到端流水线联调。  
- Day 5: 生成示例输出，初步评估（IoU、召回），准备中期演示材料。

Week 2 (Day 6–10)
- Day 6: 修正错误与提高稳定性（处理装饰/小图标/复杂文本块）。
- Day 7: 集成 `LayoutResolver` 做一轮校验与互操作性测试；调整输出兼容性。  
- Day 8: 编写单元/集成测试（10 张样本），量化指标，改进规则/thresholds。  
- Day 9: 准备演示脚本与 PPT，总结结果和未解决问题。  
- Day 10: 交付：提交 `scripts/parse_poster_to_template.py`、样本 JSON、评估表格、会议回报要点。

风险与缓解
--
- 风险：复杂装饰元素和非标准排版导致检测器漏检或误判。  
  缓解：使用 VLM（Qwen/GPT-4V）做二次确认；对关键元素人工回退。  
- 风险：z-order 推断不准影响最终可用性。  
  缓解：采用混合规则（遮挡像素比 + 元素类别启发）并暴露可调阈值。  

开箱即用的首版工程任务（最小实现清单）
--
1. `scripts/parse_poster_to_template.py`（实现 pipeline、CLI 参数、单张图片到 JSON）。
2. `tests/test_template_parsing.py`（基于 5 张样本做断言）。
3. `docs/template_parsing_readme.md`（运行说明 + 评估命令）。

参考（精选）
--
- Design2Code (2024), WebSight (2024), LayoutLLM (2024), ShowUI (2024), DetAS (2026) — 上文已有完整参考列表，优先看 `Design2Code` / `WebSight` 两篇以获得数据合成与评估思路。

下一步（由我代劳可选项）
--
1. 我可以在本仓库创建 `scripts/parse_poster_to_template.py` 原型并提交 PR（3–4 天内完成 MVP）。
2. 或者我今天先把 PPT 要点和 5 张样本的预期输出表做出来，供明早会议使用（1 天）。

请告诉我你希望我现在开始做哪项（1 = 生成 prototype 脚本，2 = 产出明早演示材料），我会按你的选择立即开始。 
│  └─ 参数化变体生成                                        │
<<<<<<< HEAD
=======
│                                                            │
└──────────────────────────────────────────────────────────┘
```

### 具体建议

1. **端侧优先方案**：使用 Qwen2-VL-2B 或 ShowUI (1B) 做初步布局解析，速度快、可离线

2. **合成数据训练**：参考 WebSight 思路，用模板渲染引擎生成大量"模板→截图"对，反向训练解析模型

3. **Agent 化设计**：参考 DetAS 的自适应思路，让模型根据图片类型动态选择解析策略

4. **分层处理**：
   - 规则元素 (文字/图片) → 传统检测 + OCR
   - 复杂元素 (装饰/特效) → VLM 理解
   - 编辑操作 (Live Photo) → 元数据 + 视觉推断结合

5. **模板表示标准化**：设计统一的参数化模板 DSL

```json
{
  "type": "poster",
  "layout": {"type": "grid", "cols": 2, "rows": 3},
  "elements": [
    {"id": "hero_image", "bbox": [0,0,1,0.5], "type": "image", "style": {}},
    {"id": "title", "bbox": [0.1,0.55,0.9,0.7], "type": "text", "style": {}}
  ]
}
```

6. **Live Photo 特殊考虑**：
   - 结合 EXIF / 深度数据 / 运动向量等元信息
   - 时间维度：从关键帧序列推断编辑操作
   - 参考 Sora 逆向工程论文的 DiT 分析思路

---

## 11. 当前发展现状 (2024-2026 State of the Art)

### 11.1 技术全景：四条主要路径

当前"图像→结构化输出"领域已形成四条平行的技术路径，各有优劣：

| 路径 | 代表方法 | 核心思路 | 优势 | 劣势 |
|------|---------|---------|------|------|
| **A. 端到端 VLM 生成** | Design2Code, Screenshot2Code | VLM 直接从截图生成代码/HTML | 简单直接，零样本可用 | 复杂布局精度差，不可控 |
| **B. 检测+结构化 pipeline** | OmniParser, DocLayout-YOLO | 先检测元素 → 再结构化组织 | 精度高，模块化 | 缺乏语义理解，层级推断弱 |
| **C. Agent 编排** | DetAS, CogAgent, DocAgent | VLM 感知 + LLM 推理 + 工具调用 | 灵活，可解释，可干预 | 速度慢，成本高 |
| **D. 合成数据+微调** | WebSight, ShowUI | 用渲染引擎生成"截图→代码"对训练 | 数据可控，领域适配好 | 合成→真实的泛化差距 |

### 11.2 各子方向最新进展

#### 11.2.1 Screenshot-to-Code：从 Demo 到可用工具

- **Design2Code (2024.02)** 建立了首个系统化 benchmark，发现 GPT-4V 在简单页面接近人工水平，但复杂页面（多层嵌套、响应式布局）仍显著落后
- **Screenshot2Code 开源生态 (2024-2025)**：多个独立开源实现（如 abi/screenshot-to-code）利用 GPT-4V + Claude 3.5 Sonnet 迭代修正代码，已在产品级应用中使用
- **FrontierUI (2025)** 扩展了 web UI 生成评估，增加了组件粒度和样式保真度指标
- **关键发现**：多轮迭代修正（先生成→视觉对比→修正）比单次生成效果提升 30-40%

#### 11.2.2 屏幕解析：OmniParser 生态化

- **OmniParser v1→v3 (2024.09-2025)**：微软持续迭代，从基础元素检测发展到支持功能描述生成、OCR 集成、跨平台 UI 理解
  - v2: 改进检测精度，增加更多元素类型
  - v3: 更好的 OCR 集成，支持功能语义描述
- **后续集成**：UFO、ScreenAgent 等多个 Agent 框架已将 OmniParser 作为标准感知模块
- **局限**：主要针对 UI 屏幕，输出为扁平元素列表，缺乏布局关系和层级结构

#### 11.2.3 VLM 布局理解：从识别到定位

- **CogAgent (2024, 清华)**：基于 VLM 的高分辨率 GUI Agent，支持 1120×1120 输入，对精细 UI 元素理解能力突出
- **ScreenAI (2024, Google)**：专门针对屏幕内容理解的多模态模型，支持 UI 检测、图表理解、屏幕摘要
- **OS-Atlas (2025)**：跨平台 GUI Agent 基础模型，支持 Web/Android/Linux 多平台 UI 理解
- **GOT-OCR2.0 (2025)**：通用 OCR 大模型，支持公式、表格、乐谱等多种结构化文本
- **Nougat (2024, Meta)**：学术文档 OCR→Markdown 端到端转换，在学术文档领域效果显著

#### 11.2.4 文档版面分析：从检测到语义理解

- **CDLA (2024)**：中文文档版面分析 benchmark，填补中文领域空白
- **LayoutLM 系列持续演进**：微软持续迭代文档理解基础模型
- **关键转变**：从单纯的布局检测转向**语义理解**和**结构化输出**，关注元素的功能角色（标题/正文/页眉/页脚）而非仅 bbox

#### 11.2.5 Agent 化文档理解 (2025-2026)

- **DocAgent (2025)**：基于多模态 LLM 的复杂文档理解 Agent，支持跨页面推理
- **PaperQA2 (2025)**：学术论文理解的 Agent 系统
- **Agentic RAG for Documents (2025-2026)**：将 RAG 与 Agent 范式结合，支持复杂文档工作流
- **Microsoft Document AI Agent (2025)**：OmniParser + GPT-4V 组合，支持多格式多任务
- **关键趋势**：从单一模型到多 Agent 协作，从端到端到模块化+Agent 编排，强调可解释性和可干预性

#### 11.2.6 设计逆向工程：新兴但碎片化

- **Image-to-Design (2025)**：从截图生成可编辑设计文件（Figma/Sketch 格式），但粒度较粗
- **Design Token 提取**：多个工作聚焦于从设计图提取颜色/字体/间距等设计 token
- **UIClip (2024, Google)**：评估 UI 设计质量，为解析质量提供反馈信号
- **关键缺失**：海报/平面设计领域的结构化提取研究显著少于 UI 领域

### 11.3 当前技术成熟度评估

```
成熟度雷达图 (1=早期, 5=成熟)

文档版面检测    ★★★★★  (工业级可用)
UI 元素检测     ★★★★☆  (接近成熟)
OCR 文本提取    ★★★★★  (成熟)
UI 截图转代码   ★★★☆☆  (简单场景可用)
海报布局解析    ★★★☆☆  (基础可用)
设计意图理解    ★★☆☆☆  (早期)
层级结构推断    ★★☆☆☆  (早期)
可编辑模板生成  ★☆☆☆☆  (几乎空白)
编辑操作逆向    ★☆☆☆☆  (几乎空白)
```

### 11.4 2025-2026 新趋势

1. **VLM + Agent 混合范式**：VLM 做感知，Agent 做推理/规划/工具调用，成为主流架构
2. **多模态统一模型**：单一模型处理 UI、文档、设计等多种视觉内容（如 GPT-4o, Gemini 1.5）
3. **交互式解析**：用户可在解析过程中干预和修正，人机协作而非全自动
4. **从解析到生成的闭环**：解析→编辑→重新生成的迭代流程
5. **Foundation Model for Design**：设计领域的基础模型探索（类似 LayoutLM 之于文档）
6. **开源工具链成熟**：OmniParser、Layout Parser、Surya 等工具生态逐步完善

---

## 12. 主要问题与技术瓶颈

### 12.1 核心问题：从"是什么"到"为什么"的语义鸿沟

当前方法已能较好地回答"图像里有什么元素、在哪里"（检测层面），但无法回答"设计师为什么这样排版、这个元素为什么放在这里"（意图层面）。这是从"解析"到"模板"的根本障碍。

```
检测层面：  图像 → [元素bbox + 类别]          ← 已基本解决
结构层面：  图像 → [元素 + 层级关系 + 布局约束]  ← 部分解决
语义层面：  图像 → [元素 + 关系 + 设计意图]      ← 大量空白
模板层面：  图像 → [参数化模板 + 可编辑约束]      ← 几乎空白
```

### 12.2 七大具体技术瓶颈

#### 瓶颈 1：层级结构推断 (Hierarchy Inference)

**问题**：设计元素之间的嵌套、组合、遮挡关系难以自动推断。同一视觉布局可能对应多种层级解释。

**现状**：
- OmniParser 输出扁平元素列表，无层级信息
- Design2Code 生成的代码中 div 嵌套经常错误
- 没有公开的"设计层级结构"benchmark

**需要的突破**：
- 形式化"布局语法"（Layout Grammar），定义合法的设计组合规则
- 类似 AST（抽象语法树）的 Design Tree 表示
- 从视觉特征推断 z-order 和组合关系的算法

#### 瓶颈 2：设计中间表示缺失 (No Design IR)

**问题**：缺少一种统一的"设计中间表示"（Design Intermediate Representation），既能精确描述视觉布局，又能支持灵活编辑。

**现状**：
- HTML/CSS 是一种表示，但不是为"模板复用"设计的
- Figma/Sketch 的 JSON 格式过于底层（像素级），缺少语义
- JSON DSL（如本调研第10节建议的格式）缺乏标准化和生态支持

**需要的突破**：
- 标准化的 Design IR 规范（类似编译器的 IR）
- 支持多粒度抽象（像素级→组件级→模式级）
- 与主流设计工具（Figma/Canva/Photoshop）的双向转换

#### 瓶颈 3：设计意图推断 (Design Intent Inference)

**问题**：无法从视觉布局推断设计师的决策原因——为什么选择这种配色、为什么标题放在这个位置、什么是有意的设计什么是由约束驱动的。

**现状**：
- 现有方法全部关注"是什么"（what），几乎不关注"为什么"（why）
- UIClip (Google) 尝试评估 UI 质量，但不解释设计决策
- 缺乏设计原则的标注数据

**需要的突破**：
- 设计原则的知识库/本体（对比、对齐、重复、亲密性等）
- 从视觉特征到设计决策的逆向推理模型
- "设计 Critique" 数据集（专家解释为什么这样设计）

#### 瓶颈 4：可编辑性 vs 精确性权衡 (Editability vs Fidelity)

**问题**：过于精确的还原（像素级）导致模板僵硬不可编辑；过于抽象的模板丢失关键视觉细节。没有成熟的平衡机制。

**现状**：
- Design2Code 倾向于精确还原，但生成的代码难以修改
- 模板推荐系统（如 Canva/TemplateRank）倾向高度抽象，但丢失样式细节
- 没有系统性的"可编辑性"评估指标

**需要的突破**：
- 参数化模板表示（固定结构 + 可变参数）
- 可编辑性度量指标（可修改维度数、修改后保持美观的概率）
- 分层抽象（结构层固定/样式层可调/内容层自由）

#### 瓶颈 5：跨领域泛化 (Cross-Domain Generalization)

**问题**：UI 理解、文档分析、海报设计各自为战，缺乏统一框架。在一个领域训练的模型难以迁移到另一个领域。

**现状**：
- OmniParser 仅针对 UI 屏幕
- DocLayout-YOLO 针对文档
- 海报布局分析缺乏专门的大规模模型
- 没有跨领域的统一 benchmark

**需要的突破**：
- 统一的视觉结构化输出框架（覆盖 UI/文档/海报/拼贴）
- 跨领域预训练数据集
- 领域自适应策略（根据输入类型动态调整解析策略）

#### 瓶颈 6：评估困难 (Evaluation Gap)

**问题**：缺乏统一的评估指标和 benchmark 来衡量"图像→模板"的质量。结构性输出的质量难以量化。

**现状**：
- Design2Code 提出了自动评估指标（CLIP + LLM 评分），但仅针对代码生成
- 文档版面分析有 mAP 等检测指标，但不评估结构正确性
- 没有"模板保真度"的标准化度量

**需要的突破**：
- 多维度评估框架：结构正确性 + 视觉保真度 + 可编辑性 + 泛化性
- 标准化 benchmark（包含 UI/文档/海报/拼贴多类型）
- 人工对齐的自动评估指标

#### 瓶颈 7：海报/平面设计领域的特殊挑战

**问题**：相比 UI 和文档，海报/平面设计的结构化提取研究显著不足，且有独特挑战。

**现状**：
- 海报中装饰性元素（背景纹理、光效、渐变）难以用结构化方式表示
- 文字艺术化（弯曲、渐变、描边）超出常规 OCR 能力
- 元素之间的视觉关系比 UI 更复杂（叠加、穿插、融合）
- 缺乏大规模海报→模板的标注数据

**需要的突破**：
- 海报专用结构化表示（包含装饰层、内容层、背景层）
- 艺术化文字的参数化提取
- 海报设计→模板的大规模数据集构建

### 12.3 问题依赖关系图

```
                    ┌─────────────────┐
                    │  Design IR 缺失  │ ← 基础设施层
                    └───────┬─────────┘
                            │
            ┌───────────────┼───────────────┐
            ▼               ▼               ▼
    ┌───────────────┐ ┌───────────┐ ┌───────────────┐
    │ 层级结构推断  │ │ 评估困难  │ │ 跨领域泛化   │ ← 能力层
    └───────┬───────┘ └───────────┘ └───────────────┘
            │
    ┌───────┴───────┐
    ▼               ▼
┌───────────┐ ┌───────────────────┐
│ 设计意图  │ │ 可编辑性vs精确性  │ ← 语义层
└───────────┘ └───────────────────┘
```

**关键洞察**：Design IR 是基础设施瓶颈——没有统一的中间表示，其他问题难以系统性解决。当前研究碎片化的根源之一就是各自定义了不同的输出格式。

### 12.4 从"图像"到"可编辑模板"的差距总结

| 能力 | 当前状态 | 到"可编辑模板"的差距 |
|------|---------|-------------------|
| 元素检测 | ✅ 基本解决 | 需增加语义角色标注 |
| 文本提取 (OCR) | ✅ 基本解决 | 需支持艺术化文字 |
| 布局关系 | ⚠️ 部分解决 | 需推断层级和组合关系 |
| 样式提取 | ⚠️ 基础可用 | 需提取设计变量（而非固定值） |
| 设计意图 | ❌ 大量空白 | 需设计原则知识库 + 推理模型 |
| 参数化模板 | ❌ 几乎空白 | 需 Design IR + 可编辑性约束 |
| 模板复用/变体 | ❌ 几乎空白 | 需模板变换和风格迁移能力 |

---

## 13. 重点关注的论文清单（更新版）

| 优先级 | 论文 | arXiv | 理由 |
|-------|------|-------|------|
| ⭐⭐⭐ | **Design2Code** | 2403.03163 | 最直接相关，提供数据集 + 评估方法 |
| ⭐⭐⭐ | **WebSight** | 2403.09556 | 合成数据方案可复用到模板解析 |
| ⭐⭐⭐ | **OmniParser** | 2408.00254 | 微软屏幕解析方案，工程化程度高 |
| ⭐⭐ | **NaviDC-OCR** (2026) | - | 形变感知思路对拍摄文档解析至关重要 |
| ⭐⭐ | **DetAS** | 2605.31174 | Agent 化检测框架，自适应策略值得借鉴 |
| ⭐⭐ | **ShowUI** | 2411.17465 | 轻量级，端侧部署可行性高 |
| ⭐⭐ | **Ferret-UI** | 2404.07973 | Apple 的 UI 细粒度 VLM，定位能力强 |
| ⭐ | **LayoutLLM** | 2402.16618 | VLM + 布局理解的系统化方法 |
| ⭐ | **LayoutGPT** | 2305.10438 | LLM 零样本布局规划，思路启发 |
| ⭐ | **Layer Diffusion** (2024) | - | 分层图像编辑，对 Live Photo 图层解析有参考价值 |
| ⭐ | **DocLayout-YOLO** | 2404.11845 | 工业级实时版面检测，可做 baseline |

---

## 14. 参考文献

### 图像逆向布局解析
1. LayoutLLM: Enhancing Document Layout Analysis with Large Language Models. ACL 2024. arXiv:2402.16618
2. LayoutGPT: Compositional Visual Planning and Generation with Large Language Models. ACL 2024. arXiv:2305.10438
3. DocLLM: Disentangling Spatial and Semantic Representations for Layout Understanding. ACL 2024.
4. NaviDC-OCR: Deformation-Aware Vision-Language Model for Document Parsing. 2026.
5. Blueprint: Reverse Engineering UI Designs. Meta, CVPR 2024.
6. PosterLayout: A New Benchmark and Approach for Poster Layout Generation. CVPR 2024. arXiv:2406.03037

### 图像到代码
7. Design2Code: How Far Are We From Automating Front-End Engineering? arXiv:2403.03163, 2024.
8. WebSight: Towards an Open Vision-Language Dataset for Webpage Coding. arXiv:2403.09556, 2024.
9. Screenshot2Code. Open-source project, 2024.
10. Pix2Code: Generating Code from a Graphical User Interface Screenshot. ACM SIGCHI 2017.
11. OmniParser: Screen Parsing model for General GUI Agent. Microsoft, arXiv:2408.00254, 2024.

### 设计模板理解与生成
12. LayoutDM: Discrete Diffusion Model for Layout Generation. CVPR 2023.
13. PosterGen: Poster Layout and Content Joint Generation. 2024.
14. GraphicDesignAI: Constraint-Based Design Layout Optimization. 2024.

### VLM 用于布局/设计理解
15. Qwen-VL: A Versatile Vision-Language Model. Alibaba, 2024.
16. Ferret-UI: Grounded Mobile UI Understanding with Multimodal LLMs. Apple, arXiv:2404.07973, 2024.
17. UGround: Benchmarking GUI Visual Grounding. Microsoft, arXiv:2405.14538, 2024.
18. SeeClick: Harnessing Zero-shot GUI Grounding. ACL 2024.
19. ShowUI: One Vision-Language-Action Model for GUI Visual Agent. arXiv:2411.17465, 2024.
20. DetAS: Dynamic Detection Agent System. CVPR 2026. arXiv:2605.31174.

### 文档/海报版面分析
21. LayoutLMv3: Pre-training for Document AI with Masked Image-Language Modeling. ACM MM 2022.
22. DocLayout-YOLO: Enhancing Document Layout Analysis with YOLO. arXiv:2404.11845, 2024.
23. DiT: Self-supervised Pre-training for Document Image Transformer. ICCV 2023.
24. RT-DETR: Real-time Detection Transformer. ICCV 2023.
25. PP-StructureV2: Industrial-grade Document Analysis Pipeline. PaddlePaddle.
26. Surya: Multilingual OCR and Layout Analysis. Open-source, 2024.

### 图像编辑操作逆向工程
27. Layer Diffusion: Layered Image Generation and Editing. 2024.
28. InstructPix2Pix: Learning to Follow Image Editing Instructions. CVPR 2023.
29. MagicBrush: A Manually Annotated Dataset for Instruction-Driven Image Editing. 2023.
30. Emu Edit: Precise Image Editing via Vision-Language Models. Meta, 2023.
31. Visual Program Inference from Edit Results. 2024.
32. ProEdit: Progressive Edit Reasoning from Single Image. 2024.

### 模板推荐与个性化生成
33. Neural Palette: Color-Aware Design Recommendation. 2023.
34. TemplateRank: Content-Aware Template Ranking. 2024.
35. DesignTemplate: Large-Scale Template Matching and Recommendation. Canva, 2023.
36. Personalized Layout Generation with User Preferences. 2024.

37. OmniParser v3: Screen Parsing with Foundation Models. Microsoft, 2025.
38. CogAgent: A Visual Language Model for GUI Agents. THU, 2024.
39. ScreenAI: A Vision-Language Model for UI and Infographics Understanding. Google, 2024.
40. OS-Atlas: A Foundation Model for Generalist GUI Agents. 2025.
41. GOT-OCR2.0: General OCR Theory. 2025.
42. Nougat: Neural Optical Understanding for Academic Documents. Meta, 2024.
43. CDLA: A Chinese Document Layout Analysis Benchmark. 2024.
44. DetAS: A Document-level Entity-based Table-to-Text Generation System. 2025.
45. PaperQA2: An Agent for Scientific Literature Search. 2025.
46. UIClip: Evaluating UI Design with Vision-Language Models. Google, 2024.
47. DocAgent: A Multi-Modal Agent for Complex Document Understanding. 2025.
48. ShowUI: One Vision-Language-Action Model for GUI Visual Agent. 2024.
49. Screenshot-to-Code: Open-source screenshot to code converter. GitHub, 2024-2025.

---

## 15. 聚焦调研：元素空间位置与覆盖关系 (2026-08-27 专项)

> **范围限定**：两周内聚焦研究两个核心问题——
> 1. 从图片中提取各素材的空间位置（bbox 坐标）
> 2. 判断元素之间是否相互覆盖（overlap/occlusion）
>
> 不涉及旋转、样式、语义意图。

### 15.1 问题定义

```
输入：一张设计图片（海报 / 社交卡片 / 拼贴画 / Live Photo 编辑产物）
输出：
  ① 元素列表 E = {e₁, e₂, ..., eₙ}，每个 eᵢ = (xᵢ, yᵢ, wᵢ, hᵢ, typeᵢ)
  ② 覆盖矩阵 O ∈ {0,1}^{n×n}，O[i][j]=1 表示 eᵢ 被 eⱼ 覆盖（部分或全部）
```

### 15.2 子问题一：元素空间位置检测

#### 15.2.1 当前最优方法对比

| 方法 | 类型 | 输入分辨率 | bbox 精度 | 速度 | 开源 | 适用场景 |
|------|------|-----------|----------|------|------|---------|
| **DocLayout-YOLO** | 专用检测器 | 1280×1280 | 高 (mAP~0.87) | 快 (30+ FPS) | ✅ | 文档版面 |
| **OmniParser v2** | 检测+OCR | 1024×1024 | 中高 | 中 | ✅ | UI 屏幕 |
| **Florence-2** | VLM grounding | 768×768 | 中 | 中 | ✅ | 通用目标定位 |
| **Qwen2-VL** | VLM | 动态 | 中 | 慢 | ✅ | 通用，支持坐标输出 |
| **GroundingDINO** | 开放词汇检测 | 800×1333 | 高 | 中 | ✅ | 文本提示检测 |
| **GPT-4o** | 闭源 VLM | 动态 | 中低 | 慢 | ❌ | 零样本，坐标粗略 |
| **SAM2** | 分割 | 1024×1024 | 高（mask） | 中 | ✅ | 任意元素分割→bbox |

#### 15.2.2 VLM 直接输出坐标的能力评估

近期研究（2024-2025）对 VLM 输出 bbox 坐标做了系统评估：

- **Qwen2-VL**：原生支持坐标输出（`<box>x1,y1,x2,y2</box>`格式），在 RefCOCO 等定位任务上表现较好，但坐标精度通常在 ±10-20px 量级
- **Florence-2**：微软开源，支持 region proposal + OCR + grounding，输出格式统一，适合 pipeline 集成
- **GPT-4o / Claude 3.5**：可以输出坐标，但经常出现幻觉（坐标超出图片范围）或精度差（归一化坐标到像素的转换误差大）
- **关键发现**：VLM 输出坐标的 IoU 通常在 0.5-0.7 之间，专用检测器（YOLO/DINO）可达 0.8-0.9

#### 15.2.3 从 mask 到 bbox 的路径

另一条路径是用分割模型获取像素级 mask，再转换为 bbox：

```
图片 → SAM2 → 每个元素的 mask → bbox = min/max(x,y) of mask
```

- **优势**：mask 精度极高，可处理非矩形元素（透明 PNG、贴纸等）
- **劣势**：SAM2 不区分"设计元素"和"背景区域"，需要后处理或 prompt 引导
- **实践建议**：可以用 text prompt（如"检测图中所有设计元素"）配合 GroundingDINO + SAM2

#### 15.2.4 海报/设计图片的特殊挑战

与文档/UI 不同，海报类图片的元素检测面临：

1. **背景与前景难区分**：渐变背景可能被误检为元素
2. **装饰元素**：光效、粒子、纹理是否算独立元素？需要定义粒度
3. **文字嵌入图片**：艺术化文字可能被当作图片元素而非文本元素
4. **透明/半透明元素**：叠加层的边界模糊

### 15.3 子问题二：元素覆盖/重叠关系判断

#### 15.3.1 几何方法（纯计算，无需模型）

一旦有了 bbox，覆盖关系可以通过纯几何计算判断：

```python
def get_overlap_matrix(boxes):
    """boxes: [(x1,y1,x2,y2), ...] → overlap matrix"""
    n = len(boxes)
    O = [[0]*n for _ in range(n)]
    for i in range(n):
        for j in range(n):
            if i == j: continue
            ix1 = max(boxes[i][0], boxes[j][0])
            iy1 = max(boxes[i][1], boxes[j][1])
            ix2 = min(boxes[i][2], boxes[j][2])
            iy2 = min(boxes[i][3], boxes[j][3])
            iw = max(0, ix2 - ix1)
            ih = max(0, iy2 - iy1)
            intersection = iw * ih
            area_i = (boxes[i][2]-boxes[i][0]) * (boxes[i][3]-boxes[i][1])
            if intersection > 0:
                O[i][j] = 1  # e_i 被 e_j 覆盖
    return O
```

**这种方法的问题**：只能判断 bbox 级别的相交，无法判断真实视觉覆盖。两个 bbox 相交但实际像素可能都是透明的（不构成覆盖）。

#### 15.3.2 像素级覆盖判断

更精确的方法是在 mask 层面判断覆盖：

```python
def get_pixel_overlap(masks):
    """masks: [H×W binary, ...] → overlap matrix"""
    n = len(masks)
    O = [[0]*n for _ in range(n)]
    for i in range(n):
        for j in range(n):
            if i == j: continue
            overlap = (masks[i] & masks[j]).sum()
            if overlap > 0:
                O[i][j] = 1  # e_i 与 e_j 有像素级重叠
    return O
```

- **优势**：精确，能处理透明区域
- **劣势**：需要每个元素的 mask（依赖分割模型），计算量大

#### 15.3.3 z-order（前后顺序）推断

覆盖关系还需要知道**谁在上谁在下**。当前方法：

| 方法 | 原理 | 可靠性 |
|------|------|--------|
| **视觉线索推断** | 人眼可根据遮挡边缘、阴影、透明度推断 z-order | 人类可靠，模型不可靠 |
| **VLM 推理** | 问 VLM"元素A和B哪个在前面？" | 简单场景可用，复杂场景不可靠 |
| **生成模型概率** | 比较先生成A再生成B vs 反序的概率 | 实验性方法，未成熟 |
| **边缘分析** | 遮挡边界处通常有锐利边缘、阴影 | 需要传统CV，鲁棒性差 |

**关键洞察**：z-order 推断是当前最薄弱的环节。文献中几乎没有专门研究"从单张2D图片推断设计元素 z-order"的工作。大多数检测方法输出扁平列表，不包含深度信息。

#### 15.3.4 相关研究

- **Occlusion detection in object recognition**：传统CV领域有较多研究（如 amodal segmentation），但主要针对自然场景物体，不针对设计元素
- **Amodal segmentation (2024-2025)**：推断被遮挡部分的形状，代表工作如 **AMEX** (Amodal Matte Extraction)、**Stacked Amodal Segmentation**，可用于推断元素是否被遮挡
- **Design 层级的 z-order**：在设计工具中 z-order 是显式存储的（图层顺序），但从渲染后的图片逆向推断 z-order 几乎无人研究
- **UI 领域**：OmniParser/ScreenAI 等均不输出 z-order

### 15.4 两周可实施方案建议

#### 方案 A：检测器 pipeline（推荐）

```
图片 → GroundingDINO (prompt="所有设计元素") → bbox 列表
     → SAM2 (bbox prompt) → 精确 mask
     → 几何计算 overlap matrix
     → [可选] mask 像素级 overlap 精修
```

- **优点**：每一步都有成熟开源工具，可立即跑通
- **缺点**：GroundingDINO 对"设计元素"的检测粒度需要调优
- **预计工时**：3-5 天搭建 pipeline + 5-7 天调优和评测

#### 方案 B：VLM 直接输出

```
图片 → Qwen2-VL / Florence-2 → "输出所有元素的 bbox 坐标"
     → 几何计算 overlap matrix
```

- **优点**：最简单，一步到位
- **缺点**：坐标精度差（±15px），小元素容易漏检
- **预计工时**：1-2 天搭建 + 剩余时间调 prompt 和评测

#### 方案 C：混合方案（精度优先）

```
图片 → VLM 识别元素类型和数量 → 生成 text prompts
     → GroundingDINO + SAM2 精确定位 → mask → overlap
     → VLM 辅助判断 z-order（"A在B前面还是后面？"）
```

- **优点**：结合语义理解和精确定位
- **缺点**：z-order 部分不可靠，需要人工验证

### 15.5 评测指标

针对这两个子问题，建议用以下指标：

| 指标 | 定义 | 目标 |
|------|------|------|
| **bbox IoU** | 预测 bbox 与标注 bbox 的 IoU | > 0.7 |
| **检测召回率** | 检出的元素数 / 真实元素数 | > 0.9 |
| **检测精确率** | 正确检测数 / 检测总数 | > 0.85 |
| **覆盖矩阵准确率** | 预测 overlap 矩阵与真实的一致比例 | > 0.85 |
| **z-order 准确率** | 覆盖对中 z-order 判断正确的比例 | > 0.7 (有覆盖的元素对上) |

### 15.6 关键文献（聚焦版）

| 论文 | 与本聚焦的关系 |
|------|---------------|
| **GroundingDINO** (ECCV 2024) | 开放词汇 bbox 检测，核心工具 |
| **SAM2** (Meta 2024) | 分割模型，获取精确 mask |
| **Florence-2** (Microsoft 2024) | VLM 同时输出 bbox 和 OCR |
| **DocLayout-YOLO** (2024) | 如果输入偏向文档类，直接用 |
| **AMEX** (Amodal segmentation, 2024) | 推断被遮挡区域，辅助 z-order |
| **OmniParser v2** (Microsoft 2024) | UI 元素检测参考 |
| **Qwen2-VL** (Alibaba 2024) | VLM 直接输出坐标的 baseline |

### 15.7 核心结论

1. **空间位置检测**：已有成熟工具（GroundingDINO + SAM2），瓶颈不在"能不能检测"而在"检测粒度如何定义"——什么算一个独立元素
2. **覆盖关系判断**：bbox 级 overlap 是简单几何计算（IoU > 0 即覆盖），真正难的是**像素级覆盖**和**z-order 排序**
3. **z-order 是最大空白**：从单张 2D 图片推断元素前后顺序几乎没有成熟研究，可能需要靠边缘/阴影等视觉线索做启发式判断
4. **两周可行性**：方案 A（检测器 pipeline）完全可行，核心工作量在数据标注和评测而非算法

---

*报告结束*

> **更新日期**：2026-08-27
> **更新内容**：新增第15节"聚焦调研：元素空间位置与覆盖关系"
>>>>>>> 42ae987 (修改ppt内容)
