#!/usr/bin/env python3
"""Multi-Live-Photo Composer — 多图 Live Photo 拼图系统

将多个 Live Photo 素材合成为一个 1440×1920 的拼图视频。
支持有主体和无主体素材的混合处理。

用法:
  python3 scripts/pipeline/multi_live_composer.py \
    --assets data/live_photo/test_set/ \
    --output data/live_photo/composition_output/

或指定素材列表:
  python3 scripts/pipeline/multi_live_composer.py \
    --assets a.mp4 b.mp4 c.jpg \
    --output data/live_photo/composition_output/

阶段输出 (每阶段都有可观察 MP4):
  Phase 1: phase1_asset_analysis.mp4  — Mask2Former 候选检测
  Phase 2: phase2_layout_plan.mp4     — 贪心放置动画
  Phase 3: seg_{id}.mp4 × M           — Cutie 追踪结果
  Phase 4: split_{id}.mp4 × N         — bg+subject+alpha 拆分
  Phase 5: final.mp4                  — 最终合成
"""
import argparse
import json
import sys
from pathlib import Path

# 添加项目根目录到 path
SCRIPTS_DIR = Path(__file__).resolve().parent  # scripts/pipeline/
PIPELINE_DIR = SCRIPTS_DIR  # scripts/pipeline/
PROJECT_ROOT = SCRIPTS_DIR.parent.parent  # live_photo_agent/
sys.path.insert(0, str(PROJECT_ROOT / "src"))
sys.path.insert(0, str(SCRIPTS_DIR.parent))  # scripts/ → for 'import pipeline'

CANVAS_W = 1440
CANVAS_H = 1920


def discover_assets(asset_input):
    """从输入参数发现所有 live photo 素材。

    支持: 目录(扫描.mp4) 或 文件列表
    返回: [(asset_id, video_path, jpg_path), ...]
    """
    assets = []

    if len(asset_input) == 1 and Path(asset_input[0]).is_dir():
        # 目录扫描
        d = Path(asset_input[0])
        mp4s = sorted(d.glob("*.mp4"))
        for i, mp in enumerate(mp4s):
            jpg = mp.with_suffix(".jpg")
            assets.append((f"asset_{i+1:02d}", str(mp), str(jpg) if jpg.exists() else None))
    else:
        # 文件列表
        for i, f in enumerate(asset_input):
            p = Path(f)
            if p.suffix == ".mp4":
                jpg = p.with_suffix(".jpg")
                assets.append((f"asset_{i+1:02d}", str(p), str(jpg) if jpg.exists() else None))
            elif p.suffix in (".jpg", ".png"):
                # 静态图，无视频
                assets.append((f"asset_{i+1:02d}", None, str(p)))

    return assets


def run_phase1(assets, output_dir, conda_python=None):
    """Phase 1: 资产分析 — Mask2Former 检测 + manifest.json + asset_analysis.mp4"""
    from pipeline.phase1_analyze import Phase1Analyzer

    analyzer = Phase1Analyzer(output_dir, canvas_w=CANVAS_W, canvas_h=CANVAS_H)
    manifest = analyzer.run(assets)
    return manifest


def run_phase2(manifest, output_dir):
    """Phase 2: 布局规划 — 贪心放置 + 动画可视化 + layout_plan.json"""
    from pipeline.phase2_layout import Phase2Planner

    planner = Phase2Planner(output_dir, canvas_w=CANVAS_W, canvas_h=CANVAS_H)
    layout = planner.run(manifest)
    return layout


def run_phase3(manifest, layout, output_dir, conda_python=None):
    """Phase 3: 分割 — Cutie 追踪 + seg_{id}.mp4"""
    from pipeline.phase3_segment import Phase3Segmenter

    segmenter = Phase3Segmenter(output_dir, canvas_w=CANVAS_W, canvas_h=CANVAS_H)
    segmenter.run(manifest, layout)


def run_phase4(manifest, layout, output_dir):
    """Phase 4: 拆分 — bg+subject+alpha + split_{id}.mp4"""
    from pipeline.phase4_split import Phase4Splitter

    splitter = Phase4Splitter(output_dir, canvas_w=CANVAS_W, canvas_h=CANVAS_H)
    splitter.run(manifest, layout)


def run_phase5(manifest, layout, output_dir):
    """Phase 5: 合成 — 背景拼接 + 主体叠加 → final.mp4"""
    from pipeline.phase5_compose import Phase5Composer

    composer = Phase5Composer(output_dir, canvas_w=CANVAS_W, canvas_h=CANVAS_H)
    composer.run(manifest, layout)


def main():
    ap = argparse.ArgumentParser(description="Multi-Live-Photo Composer")
    ap.add_argument("--assets", nargs="+", required=True,
                    help="素材目录或文件列表")
    ap.add_argument("--output", default="data/live_photo/composition_output",
                    help="输出目录")
    ap.add_argument("--span-ratio", type=float, default=0.25,
                    help="主体跨越比例 (0.25=主体高度的1/4)")
    ap.add_argument("--skip-phase1", action="store_true",
                    help="跳过Phase1(使用已有manifest)")
    args = ap.parse_args()

    output_dir = Path(args.output)
    output_dir.mkdir(parents=True, exist_ok=True)

    # 发现素材
    assets = discover_assets(args.assets)
    print(f"\n{'='*60}")
    print(f"  Multi-Live-Photo Composer")
    print(f"{'='*60}")
    print(f"  素材数量: {len(assets)}")
    print(f"  画布: {CANVAS_W}x{CANVAS_H}")
    print(f"  输出: {output_dir}")
    print(f"  跨越比例: {args.span_ratio}")
    for aid, vp, jp in assets:
        print(f"    {aid}: {'video' if vp else 'static'} = {vp or jp}")

    # Phase 1
    if not args.skip_phase1:
        print(f"\n{'='*60}")
        print(f"  Phase 1: 资产分析")
        print(f"{'='*60}")
        manifest = run_phase1(assets, output_dir)
    else:
        manifest_path = output_dir / "manifest.json"
        manifest = json.loads(manifest_path.read_text())
        print(f"  跳过Phase1, 使用已有 manifest: {manifest_path}")

    # Phase 2
    print(f"\n{'='*60}")
    print(f"  Phase 2: 布局规划")
    print(f"{'='*60}")
    layout = run_phase2(manifest, output_dir)

    # Phase 3
    print(f"\n{'='*60}")
    print(f"  Phase 3: 分割")
    print(f"{'='*60}")
    run_phase3(manifest, layout, output_dir)

    # Phase 4
    print(f"\n{'='*60}")
    print(f"  Phase 4: 拆分")
    print(f"{'='*60}")
    run_phase4(manifest, layout, output_dir)

    # Phase 5
    print(f"\n{'='*60}")
    print(f"  Phase 5: 合成")
    print(f"{'='*60}")
    run_phase5(manifest, layout, output_dir)

    print(f"\n{'='*60}")
    print(f"  完成!")
    print(f"{'='*60}")
    print(f"  输出目录: {output_dir}")
    print(f"  最终视频: {output_dir / 'final.mp4'}")


if __name__ == "__main__":
    main()
