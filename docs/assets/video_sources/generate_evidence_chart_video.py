from __future__ import annotations

import subprocess
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont


ROOT = Path(__file__).resolve().parents[3]
OUT_DIR = ROOT / "docs" / "assets" / "video"
SRC_DIR = ROOT / "docs" / "assets" / "video_sources"
W, H = 1920, 1080
FPS = 10
SLIDE_SECONDS = 5

FONT_REGULAR = Path(r"C:\Windows\Fonts\NotoSansTC-VF.ttf")
FONT_BOLD = Path(r"C:\Windows\Fonts\NotoSansTC-VF.ttf")


COLORS = {
    "bg": (13, 22, 33),
    "panel": (23, 35, 49),
    "panel2": (29, 45, 62),
    "text": (244, 248, 251),
    "muted": (156, 170, 181),
    "teal": (47, 194, 186),
    "green": (96, 205, 142),
    "amber": (246, 190, 82),
    "red": (239, 105, 88),
    "blue": (103, 166, 255),
    "line": (60, 78, 96),
}


def font(size: int, bold: bool = False) -> ImageFont.FreeTypeFont:
    return ImageFont.truetype(str(FONT_BOLD if bold else FONT_REGULAR), size)


def draw_text(
    d: ImageDraw.ImageDraw,
    xy: tuple[int, int],
    text: str,
    size: int,
    color: tuple[int, int, int] = COLORS["text"],
    bold: bool = False,
    anchor: str | None = None,
    spacing: int = 8,
) -> None:
    d.multiline_text(xy, text, font=font(size, bold), fill=color, anchor=anchor, spacing=spacing)


def rounded(d: ImageDraw.ImageDraw, box, radius=28, fill=COLORS["panel"], outline=None, width=2):
    d.rounded_rectangle(box, radius=radius, fill=fill, outline=outline, width=width)


def source(d: ImageDraw.ImageDraw, text: str) -> None:
    draw_text(d, (96, 1008), text, 25, COLORS["muted"])


def header(d: ImageDraw.ImageDraw, kicker: str, title: str, subtitle: str = "") -> None:
    draw_text(d, (96, 78), kicker, 30, COLORS["teal"], True)
    draw_text(d, (96, 128), title, 66, COLORS["text"], True)
    if subtitle:
        draw_text(d, (96, 218), subtitle, 34, COLORS["muted"])


def progress_bar(d, x, y, w, h, ratio, color, label, value):
    rounded(d, (x, y, x + w, y + h), radius=h // 2, fill=(34, 49, 66))
    rounded(d, (x, y, x + int(w * ratio), y + h), radius=h // 2, fill=color)
    draw_text(d, (x, y - 48), label, 32, COLORS["text"], True)
    draw_text(d, (x + w, y - 48), value, 32, color, True, anchor="ra")


def slide_base() -> tuple[Image.Image, ImageDraw.ImageDraw]:
    img = Image.new("RGB", (W, H), COLORS["bg"])
    d = ImageDraw.Draw(img)
    # Subtle diagonal bands.
    for i in range(-300, 2300, 220):
        d.line((i, 0, i - 760, 1080), fill=(16, 29, 43), width=54)
    return img, d


def donut(d, center, radius, width, ratio, color):
    x, y = center
    box = (x - radius, y - radius, x + radius, y + radius)
    d.pieslice(box, start=-90, end=270, fill=(38, 53, 70))
    d.pieslice(box, start=-90, end=-90 + int(360 * ratio), fill=color)
    inner = (x - radius + width, y - radius + width, x + radius - width, y + radius - width)
    d.ellipse(inner, fill=COLORS["panel"])


def slide_1() -> Image.Image:
    img, d = slide_base()
    header(d, "台灣高齡化 / Aging Taiwan", "正式進入超高齡社會", "2025 年底，65 歲以上人口突破 20% 門檻")
    rounded(d, (96, 310, 1824, 900), fill=COLORS["panel"])
    donut(d, (500, 610), 220, 80, 0.2006, COLORS["teal"])
    draw_text(d, (500, 575), "20.06%", 72, COLORS["teal"], True, anchor="mm")
    draw_text(d, (500, 652), "65歲以上", 34, COLORS["muted"], True, anchor="mm")
    draw_text(d, (860, 415), "4,673,155", 104, COLORS["text"], True)
    draw_text(d, (865, 545), "位 65 歲以上長者", 46, COLORS["muted"], True)
    progress_bar(d, 865, 700, 760, 42, 0.2006, COLORS["teal"], "65+ 占總人口", "20.06%")
    draw_text(d, (865, 796), "這不是未來情境，而是現在的台灣。", 44, COLORS["text"], True)
    source(d, "Source: 內政部戶政統計，中央社 2026-01-09")
    return img


def slide_2() -> Image.Image:
    img, d = slide_base()
    header(d, "肌少症 / Sarcopenia", "盛行率取決於定義", "保守表述：社區長者約一成以上可能符合肌少症定義")
    rounded(d, (96, 310, 1824, 900), fill=COLORS["panel"])
    x, y, w, h = 260, 520, 1120, 84
    rounded(d, (x, y, x + w, y + h), radius=42, fill=(38, 53, 70))
    low = int(w * 0.099 / 0.45)
    high = int(w * 0.404 / 0.45)
    rounded(d, (x + low, y, x + high, y + h), radius=42, fill=COLORS["amber"])
    draw_text(d, (x + low, y - 56), "9.9%", 36, COLORS["amber"], True, anchor="mm")
    draw_text(d, (x + high, y - 56), "40.4%", 36, COLORS["amber"], True, anchor="mm")
    draw_text(d, (x, y + 132), "國際社區長者系統性回顧：不同定義造成很大差異", 35, COLORS["muted"])
    rounded(d, (1450, 398, 1705, 653), radius=128, fill=(38, 53, 70))
    draw_text(d, (1578, 493), "12.9%", 62, COLORS["teal"], True, anchor="mm")
    draw_text(d, (1578, 574), "EWGSOP/AWGS", 28, COLORS["muted"], True, anchor="mm")
    rounded(d, (1450, 685, 1705, 792), radius=28, fill=(43, 57, 73))
    draw_text(d, (1578, 738), "台灣篩檢 proxy：34.1%", 31, COLORS["text"], True, anchor="mm")
    draw_text(d, (260, 780), "影片中避免說「精準診斷」；使用「肌力與活動能力下降風險」較安全。", 40, COLORS["text"], True)
    source(d, "Sources: Age and Ageing meta-analysis; 國民營養健康狀況變遷調查/高齡營養監測")
    return img


def slide_3() -> Image.Image:
    img, d = slide_base()
    header(d, "跌倒 / Falls", "台灣長者事故傷害死亡重要原因", "最新可查證說法：65歲以上事故傷害死亡原因第 2 位")
    rounded(d, (96, 310, 1824, 900), fill=COLORS["panel"])
    draw_text(d, (260, 480), "#2", 180, COLORS["red"], True)
    draw_text(d, (500, 508), "跌倒", 92, COLORS["text"], True)
    draw_text(d, (506, 610), "65歲以上事故傷害死亡原因", 42, COLORS["muted"], True)
    rounded(d, (1130, 405, 1580, 715), radius=36, fill=COLORS["panel2"])
    draw_text(d, (1355, 505), "25.2", 104, COLORS["amber"], True, anchor="mm")
    draw_text(d, (1355, 618), "每十萬人死亡率", 38, COLORS["text"], True, anchor="mm")
    draw_text(d, (260, 770), "因此，影片字卡不要寫「首要原因」；寫「第 2 位」更準確。", 42, COLORS["text"], True)
    source(d, "Source: 衛生福利部國民健康署，113 年死因統計")
    return img


def slide_4() -> Image.Image:
    img, d = slide_base()
    header(d, "肌少症與跌倒 / Risk Link", "跌倒風險約上升 1.5–2 倍", "比 2–3 倍更保守，也更符合 meta-analysis")
    rounded(d, (96, 310, 1824, 900), fill=COLORS["panel"])
    progress_bar(d, 310, 500, 1000, 56, 1.00 / 1.9, COLORS["muted"], "非肌少症長者 baseline", "1.00x")
    progress_bar(d, 310, 675, 1000, 56, 1.69 / 1.9, COLORS["red"], "社區肌少症長者", "OR 1.69")
    rounded(d, (1430, 448, 1700, 760), radius=36, fill=COLORS["panel2"])
    draw_text(d, (1565, 546), "OR", 48, COLORS["muted"], True, anchor="mm")
    draw_text(d, (1565, 635), "1.52", 94, COLORS["red"], True, anchor="mm")
    draw_text(d, (1565, 720), "overall pooled", 28, COLORS["muted"], True, anchor="mm")
    draw_text(d, (310, 815), "適合影片用語：肌少症長者的跌倒風險約為非肌少症長者的 1.5–2 倍。", 40, COLORS["text"], True)
    source(d, "Source: Zhang et al., Clinical Nutrition meta-analysis, PubMed PMID 30665817")
    return img


def slide_5() -> Image.Image:
    img, d = slide_base()
    header(d, "有效介入 / What Helps", "肌力 + 平衡訓練是核心", "WHO 建議 65+ 每週肌力訓練與多元平衡活動")
    rounded(d, (96, 310, 1824, 900), fill=COLORS["panel"])
    cards = [
        ("2+ 天/週", "肌力訓練", COLORS["teal"], "下肢、髖、核心、上肢"),
        ("3+ 天/週", "平衡與功能訓練", COLORS["green"], "坐站、扶椅蹲、單腳站"),
        ("150–300 分/週", "中等強度活動", COLORS["blue"], "依能力調整，少量也有益"),
    ]
    for i, (num, title, color, desc) in enumerate(cards):
        x = 180 + i * 560
        rounded(d, (x, 420, x + 480, 760), radius=34, fill=COLORS["panel2"])
        draw_text(d, (x + 240, 520), num, 60, color, True, anchor="mm")
        draw_text(d, (x + 240, 610), title, 40, COLORS["text"], True, anchor="mm")
        draw_text(d, (x + 240, 690), desc, 30, COLORS["muted"], True, anchor="mm")
    draw_text(d, (180, 828), "這就是為什麼影片聚焦 sit-to-stand、balance hold、supported squat。", 39, COLORS["text"], True)
    source(d, "Source: WHO Guidelines on Physical Activity and Sedentary Behaviour")
    return img


def slide_6() -> Image.Image:
    img, d = slide_base()
    header(d, "社區現場 / Care Setting", "近五千個據點，但動作品質很難逐一看見", "GemmaFit 的定位：運動支持工具，不是診斷工具")
    rounded(d, (96, 310, 1824, 900), fill=COLORS["panel"])
    draw_text(d, (250, 450), "4,828", 120, COLORS["teal"], True)
    draw_text(d, (252, 590), "社區照顧關懷據點", 50, COLORS["text"], True)
    draw_text(d, (252, 672), "電話問安、關懷訪視、餐飲服務、健康促進", 34, COLORS["muted"], True)
    x0, y0 = 1150, 460
    for row in range(5):
        for col in range(7):
            x = x0 + col * 66 + (row % 2) * 28
            y = y0 + row * 64
            d.ellipse((x, y, x + 34, y + 34), fill=COLORS["blue"] if (row + col) % 4 else COLORS["amber"])
    rounded(d, (1070, 795, 1695, 850), radius=28, fill=(38, 53, 70))
    draw_text(d, (1382, 822), "看得見證據，也看得見拒判理由", 31, COLORS["text"], True, anchor="mm")
    source(d, "Source: 衛福部社會及家庭署，112年9月據點概況")
    return img


def make_video(slides: list[Path], out_path: Path) -> None:
    concat = SRC_DIR / "evidence_chart_concat.txt"
    with concat.open("w", encoding="utf-8") as f:
        for slide in slides:
            f.write(f"file '{slide.as_posix()}'\n")
            f.write(f"duration {SLIDE_SECONDS}\n")
        f.write(f"file '{slides[-1].as_posix()}'\n")
    cmd = [
        "ffmpeg",
        "-y",
        "-f",
        "concat",
        "-safe",
        "0",
        "-i",
        str(concat),
        "-vf",
        f"fps={FPS},scale=1280:-1:flags=lanczos,split[s0][s1];[s0]palettegen[p];[s1][p]paletteuse",
        "-loop",
        "0",
        str(out_path),
    ]
    subprocess.run(cmd, check=True)


def main() -> None:
    OUT_DIR.mkdir(parents=True, exist_ok=True)
    SRC_DIR.mkdir(parents=True, exist_ok=True)
    slide_dir = SRC_DIR / "evidence_chart_slides"
    slide_dir.mkdir(parents=True, exist_ok=True)

    slide_images = [slide_1(), slide_2(), slide_3(), slide_4(), slide_5(), slide_6()]
    slide_paths = []
    for idx, img in enumerate(slide_images, start=1):
        path = slide_dir / f"evidence_chart_{idx:02d}.png"
        img.save(path)
        slide_paths.append(path)

    out_path = OUT_DIR / "gemmafit_evidence_charts_30s_zh.gif"
    make_video(slide_paths, out_path)

    preview = OUT_DIR / "gemmafit_evidence_charts_30s_zh_preview.jpg"
    subprocess.run(
        [
            "ffmpeg",
            "-y",
            "-ss",
            "00:00:12",
            "-i",
            str(out_path),
            "-frames:v",
            "1",
            "-update",
            "1",
            str(preview),
        ],
        check=True,
    )
    print(out_path)
    print(preview)


if __name__ == "__main__":
    main()
