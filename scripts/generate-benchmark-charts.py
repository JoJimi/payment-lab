#!/usr/bin/env python3
"""3.12/3단계 완료 기준 — benchmarks/07-summary.md의 표를 그래프로도 남긴다.

수치는 이미 각 benchmarks/0N-*.md에 실측·기록된 중앙값을 그대로 옮긴 것이다(새 측정
없음). 숫자를 바꾸려면 이 스크립트가 아니라 실제 재측정 후 이 파일의 데이터 딕셔너리를
갱신할 것 — 이 스크립트는 "이미 기록된 숫자를 시각화"만 한다.

실행: python3 scripts/generate-benchmark-charts.py
출력: benchmarks/charts/*.png

한글 라벨을 쓰므로 시스템에 한글 폰트가 있어야 한다(예: `apt-get install fonts-nanum`).
"""

import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
import matplotlib.font_manager as fm
from pathlib import Path

OUT_DIR = Path(__file__).resolve().parent.parent / "benchmarks" / "charts"
OUT_DIR.mkdir(parents=True, exist_ok=True)

# 팔레트(dataviz 스킬 reference/palette.md) — 카테고리 슬롯 고정 순서
BLUE = "#2a78d6"
ORANGE = "#eb6834"
AQUA = "#1baf7a"
YELLOW = "#eda100"
MAGENTA = "#e87ba4"
VIOLET = "#4a3aa7"
RED = "#e34948"

SURFACE = "#fcfcfb"
INK_PRIMARY = "#0b0b0b"
INK_SECONDARY = "#52514e"
INK_MUTED = "#898781"
GRIDLINE = "#e1e0d9"
BASELINE = "#c3c2b7"

for _f in fm.fontManager.ttflist:
    if "Nanum" in _f.name:
        plt.rcParams["font.family"] = _f.name
        break

plt.rcParams.update({
    "figure.facecolor": SURFACE,
    "axes.facecolor": SURFACE,
    "savefig.facecolor": SURFACE,
    "axes.unicode_minus": False,
    "text.color": INK_PRIMARY,
    "axes.edgecolor": BASELINE,
    "axes.labelcolor": INK_SECONDARY,
    "xtick.color": INK_MUTED,
    "ytick.color": INK_MUTED,
    "axes.grid": True,
    "grid.color": GRIDLINE,
    "grid.linewidth": 0.8,
    "font.size": 11,
})


def style_axes(ax, y_label=None, log=False):
    ax.spines["top"].set_visible(False)
    ax.spines["right"].set_visible(False)
    ax.spines["left"].set_visible(False)
    ax.spines["bottom"].set_color(BASELINE)
    ax.grid(axis="x", visible=False)
    ax.grid(axis="y", visible=True)
    ax.set_axisbelow(True)
    if log:
        ax.set_yscale("log")
    if y_label:
        ax.set_ylabel(y_label, color=INK_SECONDARY, fontsize=10)
    ax.tick_params(axis="both", length=0)


def bar_with_labels(ax, x, heights, color, width=0.6, fmt="{:.0f}"):
    bars = ax.bar(x, heights, width=width, color=color, edgecolor="none", zorder=3)
    for b, h in zip(bars, heights):
        ax.annotate(
            fmt.format(h),
            xy=(b.get_x() + b.get_width() / 2, h),
            xytext=(0, 4),
            textcoords="offset points",
            ha="center",
            va="bottom",
            fontsize=9,
            color=INK_SECONDARY,
        )
    return bars


def grouped_bars(ax, categories, series, colors, width=0.24, fmt="{:.0f}"):
    n = len(series)
    x = range(len(categories))
    offsets = [(-((n - 1) / 2) + i) * width for i in range(n)]
    for (label, values), color, off in zip(series, colors, offsets):
        xs = [xi + off for xi in x]
        bars = ax.bar(xs, values, width=width, color=color, edgecolor="none", zorder=3, label=label)
        for b, v in zip(bars, values):
            ax.annotate(
                fmt.format(v),
                xy=(b.get_x() + b.get_width() / 2, v),
                xytext=(0, 3),
                textcoords="offset points",
                ha="center",
                va="bottom",
                fontsize=8,
                color=INK_SECONDARY,
                rotation=0,
            )
    ax.set_xticks(list(x))
    ax.set_xticklabels(categories)


# ── 축 1: 락 전략별 (benchmarks/01-lock-strategies.md) ──────────────────────
lock_strategies = ["NONE", "PESSIMISTIC", "OPTIMISTIC(3)", "DISTRIBUTED"]
lock_tps = [253.79, 53.92, 17.59, 11.10]
lock_p50 = [165.74, 884.64, 2070, 3850]
lock_p95 = [438.10, 1210, 6910, 10030]
lock_p99 = [617.61, 1310, 10610, 10250]

fig, ax = plt.subplots(figsize=(7, 4.2))
bar_with_labels(ax, lock_strategies, lock_tps, BLUE, fmt="{:.1f}")
style_axes(ax, y_label="TPS (req/s)")
ax.set_title("락 전략별 TPS (1.13, 재고 예약 단독)", color=INK_PRIMARY, fontsize=12, loc="left")
fig.tight_layout()
fig.savefig(OUT_DIR / "01-lock-tps.png", dpi=150)
plt.close(fig)

fig, ax = plt.subplots(figsize=(8, 4.5))
grouped_bars(
    ax,
    lock_strategies,
    [("p50", lock_p50), ("p95", lock_p95), ("p99", lock_p99)],
    [BLUE, ORANGE, AQUA],
    fmt="{:.0f}",
)
style_axes(ax, y_label="응답시간 (ms, log)", log=True)
ax.set_title("락 전략별 p50/p95/p99 (1.13)", color=INK_PRIMARY, fontsize=12, loc="left")
ax.legend(frameon=False, loc="upper left", fontsize=9)
fig.tight_layout()
fig.savefig(OUT_DIR / "01-lock-latency.png", dpi=150)
plt.close(fig)

# ── 축 2: 캐시 유무 (benchmarks/06-cache.md) ────────────────────────────────
cache_categories = ["1회차\n무캐시", "1회차\n캐시", "2회차\n무캐시", "2회차\n캐시"]
cache_tps = [375.31, 455.84, 262.74, 611.74]
cache_p50 = [114.44, 55.73, 156.90, 50.92]
cache_colors = [ORANGE, BLUE, ORANGE, BLUE]

fig, axes = plt.subplots(1, 2, figsize=(10, 4.2))
bar_with_labels(axes[0], cache_categories, cache_tps, cache_colors, fmt="{:.0f}")
style_axes(axes[0], y_label="TPS (req/s)")
axes[0].set_title("TPS (캐시 있음이 항상 높음)", color=INK_PRIMARY, fontsize=11, loc="left")

bar_with_labels(axes[1], cache_categories, cache_p50, cache_colors, fmt="{:.0f}")
style_axes(axes[1], y_label="p50 (ms)")
axes[1].set_title("p50 (캐시 있음이 항상 절반 이하)", color=INK_PRIMARY, fontsize=11, loc="left")
fig.suptitle("캐시 유무별 TPS/p50 — 두 세션 모두 같은 방향(재현됨)", color=INK_PRIMARY, fontsize=12, x=0.02, ha="left")
fig.tight_layout(rect=(0, 0, 1, 0.93))
fig.savefig(OUT_DIR / "02-cache-tps-p50.png", dpi=150)
plt.close(fig)

# p99 방향 반전 시연 — "노이즈가 꼬리 지표를 지배한다"는 주장을 숫자로 보여준다
fig, ax = plt.subplots(figsize=(7, 4.2))
grouped_bars(
    ax,
    ["1회차", "2회차"],
    [("캐시 없음", [296.29, 586.30]), ("캐시 있음", [564.86, 435.66])],
    [ORANGE, BLUE],
    width=0.3,
    fmt="{:.0f}",
)
style_axes(ax, y_label="p99 (ms)")
ax.set_title("캐시 p99 — 세션 간 방향이 뒤집힌다(신뢰 불가)", color=INK_PRIMARY, fontsize=12, loc="left")
ax.legend(frameon=False, loc="upper left", fontsize=9)
fig.tight_layout()
fig.savefig(OUT_DIR / "02-cache-p99-reversal.png", dpi=150)
plt.close(fig)

# ── 축 3: 모놀리식 vs MSA (03-baseline.md / 04-saga-comparison.md) ──────────
msa_categories = ["1단계\n(모놀리식)", "2단계\norder_api", "2단계\nsaga_completion"]
msa_p50 = [1630, 190, 4930]
msa_p95 = [5540, 850, 7570]

fig, ax = plt.subplots(figsize=(7.5, 4.5))
grouped_bars(
    ax,
    msa_categories,
    [("p50", msa_p50), ("p95", msa_p95)],
    [BLUE, ORANGE],
    width=0.3,
    fmt="{:.0f}",
)
style_axes(ax, y_label="응답시간 (ms, log)", log=True)
ax.set_title("모놀리식 vs MSA — p50/p95 (VUs·측정범위 다름, 참고용)", color=INK_PRIMARY, fontsize=11, loc="left")
ax.legend(frameon=False, loc="upper left", fontsize=9)
fig.tight_layout()
fig.savefig(OUT_DIR / "03-monolith-vs-msa-latency.png", dpi=150)
plt.close(fig)

# ── 축 4: 서킷 유무 (05-circuit-breaker.md) ─────────────────────────────────
cb_categories = ["무방어", "방어 있음"]
cb_colors = [ORANGE, BLUE]

fig, axes = plt.subplots(1, 3, figsize=(13, 4.2))

bar_with_labels(axes[0], cb_categories, [18.75, 27.40], cb_colors, fmt="{:.2f}")
style_axes(axes[0], y_label="TPS (req/s)")
axes[0].set_title("TPS", color=INK_PRIMARY, fontsize=11, loc="left")

grouped_bars(
    axes[1],
    cb_categories,
    [("p50", [667, 459.5]), ("p95", [5350, 2740])],
    [BLUE, ORANGE],
    width=0.3,
    fmt="{:.0f}",
)
style_axes(axes[1], y_label="order_api 응답시간 (ms)")
axes[1].set_title("order_api p50/p95", color=INK_PRIMARY, fontsize=11, loc="left")
axes[1].legend(frameon=False, loc="upper right", fontsize=8)

bar_with_labels(axes[2], cb_categories, [16.3, 42.6], cb_colors, fmt="{:.1f}%")
style_axes(axes[2], y_label="완료율 (%)")
axes[2].set_title("Saga 타임아웃(15s) 내 완료율", color=INK_PRIMARY, fontsize=11, loc="left")

fig.suptitle("서킷 유무별 비교 — 같은 세션 연속 측정(회차 간 변동폭 큼, 방향만 신뢰)", color=INK_PRIMARY, fontsize=12, x=0.02, ha="left")
fig.tight_layout(rect=(0, 0, 1, 0.92))
fig.savefig(OUT_DIR / "04-circuit-breaker-summary.png", dpi=150)
plt.close(fig)

print("done:", sorted(p.name for p in OUT_DIR.glob("*.png")))
