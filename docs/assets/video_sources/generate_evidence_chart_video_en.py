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
    "bg": (255, 255, 255),
    "panel": (248, 250, 252),
    "panel2": (241, 245, 249),
    "text": (22, 32, 43),
    "muted": (86, 102, 117),
    "teal": (0, 137, 123),
    "green": (33, 150, 83),
    "amber": (191, 120, 24),
    "red": (205, 67, 53),
    "blue": (45, 105, 184),
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
    rounded(d, (x, y, x + w, y + h), radius=h // 2, fill=(226, 232, 240))
    rounded(d, (x, y, x + int(w * ratio), y + h), radius=h // 2, fill=color)
    draw_text(d, (x, y - 48), label, 32, COLORS["text"], True)
    draw_text(d, (x + w, y - 48), value, 32, color, True, anchor="ra")


def slide_base() -> tuple[Image.Image, ImageDraw.ImageDraw]:
    img = Image.new("RGB", (W, H), COLORS["bg"])
    d = ImageDraw.Draw(img)
    for i in range(-300, 2300, 220):
        d.line((i, 0, i - 760, 1080), fill=(249, 251, 253), width=54)
    return img, d


def donut(d, center, radius, width, ratio, color):
    x, y = center
    box = (x - radius, y - radius, x + radius, y + radius)
    d.pieslice(box, start=-90, end=270, fill=(226, 232, 240))
    d.pieslice(box, start=-90, end=-90 + int(360 * ratio), fill=color)
    inner = (x - radius + width, y - radius + width, x + radius - width, y + radius - width)
    d.ellipse(inner, fill=COLORS["panel"])


def slide_1() -> Image.Image:
    img, d = slide_base()
    header(d, "Aging Taiwan", "Taiwan Is Now Super-Aged", "By the end of 2025, people 65+ crossed the 20% threshold")
    rounded(d, (96, 310, 1824, 900), fill=COLORS["panel"])
    donut(d, (500, 610), 220, 80, 0.2006, COLORS["teal"])
    draw_text(d, (500, 575), "20.06%", 72, COLORS["teal"], True, anchor="mm")
    draw_text(d, (500, 652), "age 65+", 34, COLORS["muted"], True, anchor="mm")
    draw_text(d, (860, 415), "4,673,155", 104, COLORS["text"], True)
    draw_text(d, (865, 545), "older adults in Taiwan", 46, COLORS["muted"], True)
    progress_bar(d, 865, 700, 760, 42, 0.2006, COLORS["teal"], "Share of total population", "20.06%")
    draw_text(d, (865, 796), "This is not a future scenario. It is Taiwan now.", 44, COLORS["text"], True)
    source(d, "Source: Taiwan Ministry of the Interior statistics, reported by CNA, 2026-01-09")
    return img


def slide_2() -> Image.Image:
    img, d = slide_base()
    header(d, "Sarcopenia", "Prevalence Depends on Definition", "Conservative wording: roughly one in ten or more older adults may be affected")
    rounded(d, (96, 310, 1824, 900), fill=COLORS["panel"])
    x, y, w, h = 260, 520, 1120, 84
    rounded(d, (x, y, x + w, y + h), radius=42, fill=(226, 232, 240))
    low = int(w * 0.099 / 0.45)
    high = int(w * 0.404 / 0.45)
    rounded(d, (x + low, y, x + high, y + h), radius=42, fill=COLORS["amber"])
    draw_text(d, (x + low, y - 56), "9.9%", 36, COLORS["amber"], True, anchor="mm")
    draw_text(d, (x + high, y - 56), "40.4%", 36, COLORS["amber"], True, anchor="mm")
    draw_text(d, (x, y + 132), "International review: estimates vary widely by definition", 35, COLORS["muted"])
    rounded(d, (1450, 398, 1705, 653), radius=128, fill=(226, 232, 240))
    draw_text(d, (1578, 493), "12.9%", 62, COLORS["teal"], True, anchor="mm")
    draw_text(d, (1578, 574), "EWGSOP/AWGS", 28, COLORS["muted"], True, anchor="mm")
    rounded(d, (1450, 685, 1705, 792), radius=28, fill=(236, 241, 246))
    draw_text(d, (1578, 738), "Taiwan proxy: 34.1%", 31, COLORS["text"], True, anchor="mm")
    draw_text(d, (260, 780), "Avoid diagnostic claims. Frame this as strength and functional decline risk.", 40, COLORS["text"], True)
    source(d, "Sources: Age and Ageing meta-analysis; Taiwan nutrition and older-adult monitoring report")
    return img


def slide_3() -> Image.Image:
    img, d = slide_base()
    header(d, "Falls", "A Major Cause of Fatal Injury", "Best verified Taiwan wording: #2 accidental injury death cause among people 65+")
    rounded(d, (96, 310, 1824, 900), fill=COLORS["panel"])
    draw_text(d, (260, 480), "#2", 180, COLORS["red"], True)
    draw_text(d, (500, 508), "Falls", 92, COLORS["text"], True)
    draw_text(d, (506, 610), "accidental injury death cause, age 65+", 42, COLORS["muted"], True)
    rounded(d, (1130, 405, 1580, 715), radius=36, fill=COLORS["panel2"])
    draw_text(d, (1355, 505), "25.2", 104, COLORS["amber"], True, anchor="mm")
    draw_text(d, (1355, 618), "deaths per 100,000", 38, COLORS["text"], True, anchor="mm")
    draw_text(d, (260, 770), "Use '#2 cause' in the video, not 'leading cause'.", 42, COLORS["text"], True)
    source(d, "Source: Health Promotion Administration, Ministry of Health and Welfare, 2024 mortality statistics")
    return img


def slide_4() -> Image.Image:
    img, d = slide_base()
    header(d, "Risk Link", "Fall Risk Rises About 1.5-2x", "More conservative, and better aligned with meta-analysis evidence")
    rounded(d, (96, 310, 1824, 900), fill=COLORS["panel"])
    progress_bar(d, 310, 500, 1000, 56, 1.00 / 1.9, COLORS["muted"], "Non-sarcopenic older adults", "1.00x")
    progress_bar(d, 310, 675, 1000, 56, 1.69 / 1.9, COLORS["red"], "Community-dwelling older adults with sarcopenia", "OR 1.69")
    rounded(d, (1430, 448, 1700, 760), radius=36, fill=COLORS["panel2"])
    draw_text(d, (1565, 546), "OR", 48, COLORS["muted"], True, anchor="mm")
    draw_text(d, (1565, 635), "1.52", 94, COLORS["red"], True, anchor="mm")
    draw_text(d, (1565, 720), "overall pooled", 28, COLORS["muted"], True, anchor="mm")
    draw_text(d, (310, 815), "Suggested wording: sarcopenia is linked to about 1.5-2x higher fall risk.", 40, COLORS["text"], True)
    source(d, "Source: Zhang et al., Clinical Nutrition meta-analysis, PubMed PMID 30665817")
    return img


def slide_5() -> Image.Image:
    img, d = slide_base()
    header(d, "What Helps", "Strength + Balance Training", "WHO recommends weekly muscle strengthening and multicomponent balance activity for 65+")
    rounded(d, (96, 310, 1824, 900), fill=COLORS["panel"])
    cards = [
        ("2+ days/week", "Strength Training", COLORS["teal"], "legs, hips, core, upper body"),
        ("3+ days/week", "Balance + Function", COLORS["green"], "sit-to-stand, supported squat"),
        ("150-300 min/week", "Moderate Activity", COLORS["blue"], "adapted to ability"),
    ]
    for i, (num, title, color, desc) in enumerate(cards):
        x = 180 + i * 560
        rounded(d, (x, 420, x + 480, 760), radius=34, fill=COLORS["panel2"])
        draw_text(d, (x + 240, 520), num, 48, color, True, anchor="mm")
        draw_text(d, (x + 240, 610), title, 38, COLORS["text"], True, anchor="mm")
        draw_text(d, (x + 240, 690), desc, 28, COLORS["muted"], True, anchor="mm")
    draw_text(d, (180, 828), "This is why the demo focuses on sit-to-stand, balance hold, and supported squat.", 39, COLORS["text"], True)
    source(d, "Source: WHO Guidelines on Physical Activity and Sedentary Behaviour")
    return img


def slide_6() -> Image.Image:
    img, d = slide_base()
    header(d, "Care Setting", "Nearly 5,000 Centers, Limited Eyes on Motion", "GemmaFit's role: movement support, not diagnosis")
    rounded(d, (96, 310, 1824, 900), fill=COLORS["panel"])
    draw_text(d, (250, 450), "4,828", 120, COLORS["teal"], True)
    draw_text(d, (252, 590), "community care centers", 50, COLORS["text"], True)
    draw_text(d, (252, 672), "check-ins, visits, meals, health-promotion activities", 34, COLORS["muted"], True)
    x0, y0 = 1150, 460
    for row in range(5):
        for col in range(7):
            x = x0 + col * 66 + (row % 2) * 28
            y = y0 + row * 64
            d.ellipse((x, y, x + 34, y + 34), fill=COLORS["blue"] if (row + col) % 4 else COLORS["amber"])
    rounded(d, (1060, 795, 1710, 850), radius=28, fill=(226, 232, 240))
    draw_text(d, (1385, 822), "Visible evidence. Visible reasons to abstain.", 31, COLORS["text"], True, anchor="mm")
    source(d, "Source: Social and Family Affairs Administration, MOHW, Sept. 2023")
    return img


def make_video(slides: list[Path], out_path: Path) -> None:
    concat = SRC_DIR / "evidence_chart_concat_en.txt"
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
    slide_dir = SRC_DIR / "evidence_chart_slides_en"
    slide_dir.mkdir(parents=True, exist_ok=True)

    slide_images = [slide_1(), slide_2(), slide_3(), slide_4(), slide_5(), slide_6()]
    slide_paths = []
    for idx, img in enumerate(slide_images, start=1):
        path = slide_dir / f"evidence_chart_en_{idx:02d}.png"
        img.save(path)
        slide_paths.append(path)

    out_path = OUT_DIR / "gemmafit_evidence_charts_30s_en.gif"
    make_video(slide_paths, out_path)

    preview = OUT_DIR / "gemmafit_evidence_charts_30s_en_preview.jpg"
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

    contact_sheet = OUT_DIR / "gemmafit_evidence_charts_30s_en_contact_sheet.jpg"
    thumbs = [Image.open(p).resize((640, 360)) for p in slide_paths]
    sheet = Image.new("RGB", (1920, 720), COLORS["bg"])
    for i, thumb in enumerate(thumbs):
        sheet.paste(thumb, ((i % 3) * 640, (i // 3) * 360))
    sheet.save(contact_sheet, quality=92)

    print(out_path)
    print(preview)
    print(contact_sheet)


if __name__ == "__main__":
    main()
