import argparse
from pathlib import Path

from PIL import Image, ImageDraw, ImageFilter

from tiles import LIGHT, font, icon, pill, pill_width, rounded_mask

ROOT = Path(__file__).resolve().parent.parent
SCREENSHOTS = ROOT / "android/app/fastlane/metadata/android/en-US/images/phoneScreenshots"
OUTPUT = ROOT / "android/design/thumbnail.jpg"
SIZE = (1280, 640)
SCALE = 2
CANVAS = (SIZE[0] * SCALE, SIZE[1] * SCALE)
SCREEN = (178, 645, 902, 1920)
LEFT = 170
PHONES = [("2_library", 1500, 470, 500), ("5_explore", 2220, 470, 500),
          ("1_home", 1860, 300, 580)]


def background():
    mask = Image.linear_gradient("L").resize(CANVAS)
    colors = [Image.new("RGB", CANVAS, color) for color in (LIGHT["bottom"], LIGHT["top"])]
    return Image.composite(*colors, mask)


def shadow(canvas, box, radius, blur=50, offset=30):
    left, top, right, bottom = box
    mask = Image.new("L", CANVAS)
    ImageDraw.Draw(mask).rounded_rectangle(
        (left, top + offset, right, bottom + offset), radius,
        fill=LIGHT["shadow_strength"],
    )
    canvas.paste(LIGHT["shadow"], mask=mask.filter(ImageFilter.GaussianBlur(blur)))


def phone(name, width):
    screen = Image.open(SCREENSHOTS / f"{name}.jpg").crop(SCREEN)
    bezel = round(width * 0.025)
    display_width = width - 2 * bezel
    display_size = (display_width, round(screen.height * display_width / screen.width))
    body = Image.new("RGBA", (width, display_size[1] + 2 * bezel))
    radius = round(width * 0.09)
    draw = ImageDraw.Draw(body)
    draw.rounded_rectangle((0, 0, body.width - 1, body.height - 1), radius,
                           fill="#57504A")
    draw.rounded_rectangle((3, 3, body.width - 4, body.height - 4), radius - 3,
                           fill="#0C0B0A")
    display = screen.convert("RGB").resize(display_size, Image.LANCZOS)
    body.paste(display, (bezel, bezel), rounded_mask(display_size, radius - bezel))
    return body


def place_phones(canvas):
    for name, center, top, width in PHONES:
        body = phone(name, width)
        left = center - width // 2
        shadow(canvas, (left, top, left + width, top + body.height), width // 10)
        canvas.paste(body, (left, top), body)


def brand(canvas, top):
    size, face = 150, font(120, 750)
    logo = icon(size, LIGHT["icon"])
    shadow(canvas, (LEFT, top, LEFT + size, top + size), 32, blur=20, offset=10)
    canvas.paste(logo, (LEFT, top), logo)
    ImageDraw.Draw(canvas).text((LEFT + size + 36, top + size / 2), "Shelf", font=face,
                                fill=LIGHT["ink"], anchor="lm")


def text(canvas):
    draw = ImageDraw.Draw(canvas)
    headline = font(168, 850)
    draw.text((LEFT - 8, 440), "Your books,", font=headline, fill=LIGHT["ink"])
    draw.text((LEFT - 8, 630), "offline.", font=headline, fill=LIGHT["accent"])
    draw.text((LEFT, 870), "Find, track and read books on Android.",
              font=font(58, 600), fill=LIGHT["muted"])
    face, left = font(46, 700), LEFT
    for label in ["No account", "No ads", "Open source"]:
        pill(draw, left, 990, label, face, LIGHT["chip"], LIGHT["accent"],
             LIGHT["accent"])
        left += pill_width(label, face) + 20


def thumbnail():
    canvas = background()
    place_phones(canvas)
    brand(canvas, 190)
    text(canvas)
    return canvas.resize(SIZE, Image.LANCZOS)


def main():
    parser = argparse.ArgumentParser(description="Render the GitHub social preview.")
    parser.add_argument("output", nargs="?", type=Path, default=OUTPUT)
    output = parser.parse_args().output
    thumbnail().save(output, quality=90, optimize=True)
    print(output, f"{output.stat().st_size // 1024} KB")


if __name__ == "__main__":
    main()
