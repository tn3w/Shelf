from functools import cache
from pathlib import Path

from PIL import Image, ImageDraw, ImageFilter, ImageFont

ROOT = Path(__file__).resolve().parent.parent
FONT = ROOT / "android/app/src/main/res/font/inter.ttf"
SIZE = (1080, 1920)
CANVAS = (2160, 3840)
CENTER = CANVAS[0] // 2
PHONE_WIDTH = 1500
PHONE_TOP = 1260
BEZEL = 26
CAMERA = (540, 70, 22)
HEADLINE_WEIGHT = 850

LIGHT = dict(
    top="#FFFAF6", bottom="#F7D3BE", glow="#FFFFFF", ink="#2B2420", muted="#5E4D43",
    accent="#B4441F", on_accent="#FFFFFF", chip="#FFFFFF", shadow="#5C2610",
    shadow_strength=150, icon=("#F6EFE6", "#E4572E", "#2B2420", "#D9A441"),
)
DARK = dict(
    top="#2A1E18", bottom="#0D0A08", glow="#8A3412", ink="#FFF4EC", muted="#D8C6B9",
    accent="#FFB59C", on_accent="#5C1A00", chip="#2D2722", shadow="#000000",
    shadow_strength=200, icon=("#1C1714", "#F26B3A", "#EFE6DC", "#E0B04F"),
)


@cache
def font(size, weight):
    face = ImageFont.truetype(FONT, size)
    face.set_variation_by_axes([32, weight])
    return face


def widest(lines, face):
    return max(face.getlength(line) for line in lines)


def balanced(text, face):
    words = text.split()
    splits = [[" ".join(words[:index]), " ".join(words[index:])]
              for index in range(1, len(words))]
    return min(splits, key=lambda lines: widest(lines, face), default=[text])


def headline_size(headlines, width=1900, largest=216):
    for size in range(largest, 60, -4):
        face = font(size, HEADLINE_WEIGHT)
        if all(widest(balanced(text, face), face) <= width for text in headlines):
            return size
    return 60


def fitting(text, weight, size, width=1800):
    while font(size, weight).getlength(text) > width:
        size -= 2
    return font(size, weight)


def gradient(top, bottom):
    mask = Image.linear_gradient("L").resize(CANVAS)
    colors = [Image.new("RGB", CANVAS, color) for color in (bottom, top)]
    return Image.composite(*colors, mask)


def background(palette):
    canvas = gradient(palette["top"], palette["bottom"])
    glow = Image.new("L", (CANVAS[0] // 8, CANVAS[1] // 8))
    ImageDraw.Draw(glow).ellipse((-40, 110, 310, 400), fill=150)
    glow = glow.filter(ImageFilter.GaussianBlur(40)).resize(CANVAS, Image.BICUBIC)
    canvas.paste(palette["glow"], mask=glow)
    return canvas


def shadow(canvas, box, radius, palette, blur=90, offset=60):
    left, top, right, bottom = box
    mask = Image.new("L", CANVAS)
    ImageDraw.Draw(mask).rounded_rectangle(
        (left, top + offset, right, bottom + offset), radius,
        fill=palette["shadow_strength"],
    )
    canvas.paste(palette["shadow"], mask=mask.filter(ImageFilter.GaussianBlur(blur)))


def rounded_mask(size, radius):
    mask = Image.new("L", size)
    ImageDraw.Draw(mask).rounded_rectangle((0, 0, size[0] - 1, size[1] - 1), radius,
                                           fill=255)
    return mask


def phone(screen):
    display_width = PHONE_WIDTH - 2 * BEZEL
    scale = display_width / 1080
    display_size = (display_width, round(screen.height * display_width / screen.width))
    body = Image.new("RGBA", (PHONE_WIDTH, display_size[1] + 2 * BEZEL))
    draw = ImageDraw.Draw(body)
    draw.rounded_rectangle((0, 0, body.width - 1, body.height - 1), 132, fill="#57504A")
    draw.rounded_rectangle((5, 5, body.width - 6, body.height - 6), 127, fill="#0C0B0A")
    display = screen.convert("RGB").resize(display_size, Image.LANCZOS)
    body.paste(display, (BEZEL, BEZEL), rounded_mask(display_size, 108))
    x, y, radius = (BEZEL + value * scale for value in CAMERA)
    draw.ellipse((x - radius, y - radius, x + radius, y + radius), fill="#08080A",
                 outline="#2C2C30", width=3)
    return body


def icon(size, colors):
    ground, accent, ink, ochre = colors
    unit = size / 108

    def spot(value):
        return (54 + (value - 54) * 1.3) * unit

    def spine(draw, left, top, right, bottom, fill):
        box = (spot(left), spot(top), spot(right), spot(bottom))
        corners = (True, True, False, False)
        draw.rounded_rectangle(box, 4 * unit, fill=fill, corners=corners)

    image = Image.new("RGBA", (size, size))
    draw = ImageDraw.Draw(image)
    draw.rounded_rectangle((0, 0, size - 1, size - 1), 24 * unit, fill=ground)
    spine(draw, 34, 34, 47, 72, accent)
    spine(draw, 49, 29, 61, 72, ink)
    leaning = Image.new("RGBA", (size, size))
    spine(ImageDraw.Draw(leaning), 63, 38, 75, 72, ochre)
    pivot = (spot(66), spot(72))
    image.alpha_composite(leaning.rotate(-12, Image.BICUBIC, center=pivot))
    base = (spot(29), spot(74), spot(79), spot(79))
    ImageDraw.Draw(image).rounded_rectangle(base, 3 * unit, fill=ink)
    return image


def brand(canvas, top, palette):
    size, face = 150, font(110, 750)
    gap = 30
    left = round(CENTER - (size + gap + face.getlength("Shelf")) / 2)
    shadow(canvas, (left, top, left + size, top + size), 32, palette, blur=24, offset=12)
    logo = icon(size, palette["icon"])
    canvas.paste(logo, (left, top), logo)
    ImageDraw.Draw(canvas).text((left + size + gap, top + size / 2), "Shelf", font=face,
                                fill=palette["ink"], anchor="lm")


def pill_width(text, face):
    return face.getlength(text) + round(face.size * 2.1)


def pill(draw, left, top, text, face, fill, color, outline=None):
    height = round(face.size * 2.1)
    width = pill_width(text, face)
    box = (left, top, left + width, top + height)
    draw.rounded_rectangle(box, height // 2, fill=fill, outline=outline, width=3)
    draw.text((left + width / 2, top + height / 2), text, font=face, fill=color,
              anchor="mm")
    return width


def badge(canvas, top, text, palette):
    face = fitting(text, 700, 76)
    left = CENTER - pill_width(text, face) / 2
    pill(ImageDraw.Draw(canvas), left, top, text, face, palette["accent"],
         palette["on_accent"])


def chips(canvas, top, text, palette):
    face, gap = font(64, 700), 20
    labels = text.split(" · ")
    total = sum(pill_width(label, face) for label in labels) + gap * (len(labels) - 1)
    left = CENTER - total / 2
    draw = ImageDraw.Draw(canvas)
    for label in labels:
        left += pill(draw, left, top, label, face, palette["chip"], palette["accent"],
                     palette["accent"]) + gap


def subline(canvas, top, text, palette):
    ImageDraw.Draw(canvas).text((CENTER, top), text, font=fitting(text, 600, 84),
                                fill=palette["muted"], anchor="ma")


def header(canvas, index, headline, detail, size, palette):
    draw = ImageDraw.Draw(canvas)
    face = font(size, HEADLINE_WEIGHT)
    rows = []
    if index == 0:
        rows += [(150, lambda top: brand(canvas, top, palette)), (64, None)]
    for line in balanced(headline, face):
        rows.append((round(size * 1.08), lambda top, line=line: draw.text(
            (CENTER, top), line, font=face, fill=palette["ink"], anchor="ma")))
    below = {0: (badge, 160), 5: (chips, 134)}.get(index, (subline, 100))
    rows += [(50 if index else 64, None),
             (below[1], lambda top: below[0](canvas, top, detail, palette))]
    top = (PHONE_TOP - sum(height for height, _ in rows)) // 2 + 20
    for height, paint in rows:
        if paint:
            paint(top)
        top += height


def compose(screen, index, headline, detail, size, dark=False):
    palette = DARK if dark else LIGHT
    canvas = background(palette)
    device = phone(screen)
    left = CENTER - device.width // 2
    box = (left, PHONE_TOP, left + device.width, PHONE_TOP + device.height)
    shadow(canvas, box, 132, palette)
    canvas.paste(device, (left, PHONE_TOP), device)
    header(canvas, index, headline, detail, size, palette)
    return canvas.resize(SIZE, Image.LANCZOS)
