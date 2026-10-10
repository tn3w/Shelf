#!/usr/bin/env python3
import argparse
import datetime
import io
import json
import math
import os
import re
import shutil
import subprocess
import tempfile
import time
import urllib.request
from pathlib import Path

import numpy
from PIL import Image, ImageDraw, ImageFilter

import tiles

PACKAGE = "dev.tn3w.shelf"
DATA = f"/data/data/{PACKAGE}/files"
ROOT = Path(__file__).resolve().parent.parent
FASTLANE = ROOT / "android/app/fastlane/metadata/android"
DARK = ROOT / "android/design/screenshots/dark"
CACHE = Path(tempfile.gettempdir()) / "shelf-screenshots"
DAY = 86_400_000
STATUS_BAR = 137
TOUCH_SLOP = 21
SCREENS = ("1_home", "2_library", "3_book", "4_series", "5_explore", "6_reader",
           "7_scan", "8_settings")
FORMATS = "EPUB · PDF · FB2 · CBZ · TXT"
DEMO = ["am", "broadcast", "-a", "com.android.systemui.demo", "-e", "command"]
EMULATOR = Path(os.environ.get("ANDROID_HOME", Path.home() / "Android/Sdk")) / "emulator"
AVD = os.environ.get("SHELF_AVD", "shelf-screenshots")
SCENE_SIZE = (1440, 1920)
BOOK_HEIGHT = 700
BOOK_CENTER = 580
CUT = (1300, 1580)
VIDEO_FRAMES = 40
PAGES = 24

LOCALES = {
    "en": dict(
        folder="en-US",
        tabs=("Home", "Library", "Explore"),
        settings=("Settings", "All ("),
        cover="Cover of",
        about="About this book",
        copy=[("Your books, offline", "No account · No ads"),
              ("Switch from Goodreads", "Import shelves via CSV"),
              ("Find any book", "Fast offline search"),
              ("Never lose a series", "Series and authors"),
              ("Discover your next read", "Picks made on your phone"),
              ("Read your own files", FORMATS),
              ("Scan any cover", "Recognized on your phone"),
              ("Private by design", "No account · Offline switch")],
        scan=("Search", "Scan cover", "Scan again", 8535565),
        reader=(138052, "Alice's Adventures in Wonderland", "Lewis Carroll",
                10527843, 11),
        reading=[(82563, "Harry Potter and the Philosopher's Stone", "J. K. Rowling",
                  15155833),
                 (21745884, "Project Hail Mary", "Andy Weir", 11200092)],
        want=[(8479867, "The Name of the Wind", "Patrick Rothfuss", 11480483),
              (18012166, "Circe", "Madeline Miller", 8739376),
              (893414, "Dune", "Frank Herbert", 11481354),
              (20965973, "The Midnight Library", "Matt Haig", 10313767)],
        finished=[(27482, "The Hobbit", "J.R.R. Tolkien", 14627509),
                  (1168083, "1984", "George Orwell", 9267242),
                  (10263, "Little Prince", "Antoine de Saint-Exupéry", 10708272)],
        searches=["dune", "tolkien", "circe"],
    ),
    "de": dict(
        folder="de",
        tabs=("Startseite", "Bibliothek", "Entdecken"),
        settings=("Einstellungen", "Alle ("),
        cover="Cover von",
        about="Über dieses Buch",
        copy=[("Deine Bücher, offline", "Kein Konto · Keine Werbung"),
              ("Wechsel von Goodreads", "Regale per CSV importieren"),
              ("Finde jedes Buch", "Schnelle Offline-Suche"),
              ("Keine Reihe verpassen", "Reihen und Autoren"),
              ("Entdecke neue Bücher", "Tipps direkt auf dem Handy"),
              ("Lies deine eigenen Dateien", FORMATS),
              ("Scanne jedes Cover", "Erkannt auf dem Handy"),
              ("Privat von Grund auf", "Kein Konto · Offline-Schalter")],
        scan=("Suche", "Cover scannen", "Erneut scannen", 15157474),
        reader=(151411, "Alice im Wunderland", "Lewis Carroll", 8595966, 19778),
        reading=[(82563, "Harry Potter und der Stein der Weisen", "J. K. Rowling",
                  15155833),
                 (941669, "Tintenherz", "Cornelia Funke", 3330314)],
        want=[(2271589, "Die unendliche Geschichte", "Michael Ende", 7383413),
              (276211, "Der Schwarm", "Frank Schätzing", 1011136),
              (498463, "Der Prozess", "Franz Kafka", 997423)],
        finished=[(2271626, "Momo", "Michael Ende", 8574580),
                  (27482, "Der kleine Hobbit", "J.R.R. Tolkien", 14627509),
                  (498556, "Die Verwandlung", "Franz Kafka", 12820198),
                  (10263, "Der kleine Prinz", "Antoine de Saint-Exupéry", 10708272)],
        searches=["momo", "kafka", "funke"],
    ),
    "fr": dict(
        folder="fr",
        tabs=("Accueil", "Bibliothèque", "Explorer"),
        settings=("Paramètres", "Tout ("),
        cover="Couverture de",
        about="À propos du livre",
        copy=[("Vos livres, hors ligne", "Sans compte · Sans pub"),
              ("Quittez Goodreads", "Import CSV de vos étagères"),
              ("Trouvez vos livres", "Recherche rapide hors ligne"),
              ("Suivez vos séries", "Séries et auteurs"),
              ("Votre prochaine lecture", "Suggestions sur l’appareil"),
              ("Lisez vos fichiers", FORMATS),
              ("Scannez une couverture", "Reconnue sur l’appareil"),
              ("Privé par conception", "Sans compte · Mode hors ligne")],
        scan=("Recherche", "Scanner une couverture", "Scanner à nouveau", 14320086),
        reader=(138052, "Alice Au Pays des Merveilles", "Lewis Carroll",
                10527843, 55456),
        reading=[(82563, "Harry Potter à l'école des sorciers", "J. K. Rowling",
                  15155833),
                 (36287, "Le comte de Monte-Cristo", "Alexandre Dumas", 14566393)],
        want=[(1063588, "Les Misérables", "Victor Hugo", 12721865),
              (1230613, "L’étranger", "Albert Camus", 13151269),
              (893707, "Madame Bovary", "Gustave Flaubert", 12993424)],
        finished=[(10263, "Le petit prince", "Antoine de Saint-Exupéry", 10708272),
                  (1099280, "Vingt mille lieues sous les mers", "Jules Verne", 6573517),
                  (1168083, "1984", "George Orwell", 9267242),
                  (893414, "Dune", "Frank Herbert", 11481354)],
        searches=["camus", "jules verne", "hugo"],
    ),
    "es": dict(
        folder="es",
        tabs=("Inicio", "Biblioteca", "Explorar"),
        settings=("Ajustes", "Todo ("),
        cover="Portada de",
        about="Sobre este libro",
        copy=[("Tus libros, sin conexión", "Sin cuenta · Sin anuncios"),
              ("Ven desde Goodreads", "Importa estantes en CSV"),
              ("Encuentra cualquier libro", "Búsqueda rápida sin red"),
              ("Sigue tus sagas", "Sagas y autores"),
              ("Tu próxima lectura", "Sugerencias en tu móvil"),
              ("Lee tus archivos", FORMATS),
              ("Escanea una portada", "Reconocida en tu móvil"),
              ("Privado por diseño", "Sin cuenta · Modo sin conexión")],
        scan=("Buscar", "Escanear portada", "Escanear de nuevo", 15091493),
        reader=(503666, "Don Quijote de la Mancha", "Miguel de Cervantes Saavedra",
                14428305, 2000),
        reading=[(82563, "Harry Potter y la piedra filosofal", "J. K. Rowling",
                  15155833),
                 (278437, "La sombra del viento", "Carlos Ruiz Zafón", 10107644)],
        want=[(274505, "Cien años de soledad", "Gabriel García Márquez", 12627383),
              (1905255, "La casa de los espíritus", "Isabel Allende", 3205226),
              (14860424, "Rayuela", "Julio Cortázar", 1047466)],
        finished=[(34766796, "El Principito", "Antoine de Saint-Exupéry",
                   13499066),
                  (138052, "Alicia En El Pais de Las Maravillas", "Lewis Carroll",
                   10527843),
                  (1168083, "1984", "George Orwell", 9267242),
                  (893414, "Dune", "Frank Herbert", 11481354)],
        searches=["allende", "borges", "zafón"],
    ),
}

NODE = re.compile(
    r'text="([^"]*)"[^>]*content-desc="([^"]*)"[^>]*'
    r'bounds="\[(\d+),(\d+)\]\[(\d+),(\d+)\]"'
)


def adb(*arguments, timeout=60):
    result = subprocess.run(["adb", *arguments], capture_output=True, timeout=timeout)
    return result.stdout


def shell(*arguments):
    return adb("shell", *arguments).decode()


def protobuf_field(number, payload):
    length, size = b"", len(payload)
    while True:
        byte, size = size & 0x7F, size >> 7
        length += bytes([byte | (0x80 if size else 0)])
        if not size:
            break
    return bytes([number << 3 | 2]) + length + payload


def preferences(values):
    return b"".join(
        protobuf_field(1, protobuf_field(1, key.encode())
                       + protobuf_field(2, protobuf_field(5, text.encode())))
        for key, text in values.items()
    )


def epub(gutenberg):
    path = CACHE / f"{gutenberg}.epub"
    if not path.exists():
        url = f"https://www.gutenberg.org/ebooks/{gutenberg}.epub3.images"
        urllib.request.urlretrieve(url, path)
    return path


def library(locale, theme):
    now = int(time.time() * 1000)
    reader = locale["reader"]
    shelves = [(reader, "Reading")]
    shelves += [(book, "Reading") for book in locale["reading"]]
    shelves += [(book, "Want") for book in locale["want"]]
    shelves += [(book, "Read") for book in locale["finished"]]
    entries = [
        dict(work=book[0], shelf=shelf, updated=now - index * DAY,
             title=book[1], author=book[2], cover=book[3])
        for index, (book, shelf) in enumerate(shelves)
    ]
    progress = {reader[0]: dict(file=f"{reader[0]}.epub", section=3, page=14, pages=96)}
    today = datetime.date.today()
    pages = [34, 22, 41, 27, 30, 25, 38, 21, 29, 45, 23, 31, 26, 18]
    activity = {str(today - datetime.timedelta(days)): count
                for days, count in enumerate(pages)}
    settings = dict(onboarded=True, language=locale["language"], theme=theme,
                    wallpaperColors=False, dailyGoal=20, onlineCovers=True,
                    checkUpdates=False, lastCatalogueCheck=now, lastAppCheck=now)
    values = dict(entries=entries, progress=progress, activity=activity,
                  settings=settings, recent=locale["searches"])
    return preferences({key: json.dumps(value) for key, value in values.items()})


def seed(locale, theme):
    shell("am", "force-stop", PACKAGE)
    owner = shell("stat", "-c", "%U", f"/data/data/{PACKAGE}").strip()
    store = CACHE / "library.preferences_pb"
    store.write_bytes(library(locale, theme))
    shell("rm", "-rf", f"{DATA}/datastore", f"{DATA}/books")
    shell("mkdir", "-p", f"{DATA}/datastore", f"{DATA}/books")
    adb("push", str(store), f"{DATA}/datastore/library.preferences_pb")
    reader = locale["reader"]
    adb("push", str(epub(reader[4])), f"{DATA}/books/{reader[0]}.epub", timeout=120)
    shell("chown", "-R", f"{owner}:{owner}", DATA)
    shell("restorecon", "-R", DATA)
    shell("cmd", "locale", "set-app-locales", PACKAGE, "--locales", locale["language"])
    shell("pm", "grant", PACKAGE, "android.permission.CAMERA")


def launch():
    shell("am", "force-stop", PACKAGE)
    shell("monkey", "-p", PACKAGE, "-c", "android.intent.category.LAUNCHER", "1")


def nodes():
    xml = shell("uiautomator dump /sdcard/ui.xml > /dev/null && cat /sdcard/ui.xml")
    return NODE.findall(xml)


def wait(condition, timeout=30):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        found = condition(nodes())
        if found:
            return found
        time.sleep(0.3)
    raise TimeoutError(condition.__doc__ or "screen")


def find(label, exact=True):
    def condition(current):
        return [node for node in current
                if (label in node[:2] if exact else label in node[0] + node[1])]
    condition.__doc__ = label
    return condition


def center(node):
    _, _, left, top, right, bottom = node
    return str((int(left) + int(right)) // 2), str((int(top) + int(bottom)) // 2)


def tap(label, exact=True, timeout=30):
    shell("input", "tap", *center(wait(find(label, exact), timeout)[0]))


def covers(locale, minimum):
    def condition(current):
        found = [node for node in current if node[1].startswith(locale["cover"])]
        return found if len(found) >= minimum else []
    condition.__doc__ = f"{minimum} covers"
    return condition


def screencap():
    return adb("exec-out", "screencap", "-p")


def settled(timeout=20):
    previous = screencap()
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        time.sleep(0.8)
        current = screencap()
        if current == previous:
            return current
        previous = current
    return previous


def log(message):
    print(time.strftime("%H:%M:%S"), message, flush=True)


def swipe(distance, duration=900):
    shell("input", "swipe", "540", "1600", "540", str(1600 - distance), str(duration))


def scroll_to(label, target):
    for _ in range(12):
        found = find(label)(nodes())
        if not found:
            swipe(800)
            continue
        distance = int(found[0][3]) - target
        if abs(distance) <= 12:
            return
        slop = TOUCH_SLOP if distance > 0 else -TOUCH_SLOP
        swipe(max(-1000, min(1000, distance)) + slop, 1500)
    raise TimeoutError(label)


def text_height(capture):
    image = Image.open(io.BytesIO(capture)).convert("L").crop((0, 200, 1080, 2200))
    paper = image.getpixel((0, 0))
    ink = image.point(lambda value: 255 if abs(value - paper) > 50 else 0)
    box = ink.getbbox()
    return box[3] - box[1] if box else 0


def with_status_bar(capture, source):
    image = Image.open(io.BytesIO(capture))
    image.paste(Image.open(io.BytesIO(source)).crop((0, 0, image.width, STATUS_BAR)))
    output = io.BytesIO()
    image.save(output, "PNG")
    return output.getvalue()


def full_page():
    for _ in range(20):
        capture = settled()
        if text_height(capture) > 1600:
            return capture
        shell("input", "tap", "1000", "1200")
    raise TimeoutError("full reader page")


def download_packs(locale):
    title, button = locale["settings"]
    tap(title)
    wait(find(locale["tabs"][0]))
    if not find(button, exact=False)(nodes()):
        return
    tap(button, exact=False)
    wait(lambda current: not find(button, exact=False)(current), timeout=900)


def capture(locale, folder):
    home, library_tab, explore = locale["tabs"]
    reader = locale["reader"]
    book = locale["reading"][0]
    folder.mkdir(parents=True, exist_ok=True)

    def save(name, image):
        (folder / f"{name}.png").write_bytes(image)
        log(f"{locale['language']}: {name}")

    wait(find(reader[1]))
    wait(covers(locale, 3))
    save("1_home", settled())

    tap(library_tab)
    total = 1 + sum(len(locale[shelf]) for shelf in ("reading", "want", "finished"))
    wait(covers(locale, min(9, total)))
    save("2_library", settled())

    tap(book[1])
    wait(find(book[2]))
    details = settled()
    save("3_book", details)

    scroll_to(locale["about"], STATUS_BAR + 20)
    wait(covers(locale, 4))
    save("4_series", with_status_bar(settled(), details))

    tap(explore)
    wait(covers(locale, 3))
    save("5_explore", settled())

    tap(home)
    tap(reader[1])
    wait(lambda current: not find(home)(current))
    save("6_reader", full_page())

    shell("input", "keyevent", "BACK")
    capture_scan(locale, folder)
    launch()
    capture_settings(locale, folder)


def capture_settings(locale, folder):
    folder.mkdir(parents=True, exist_ok=True)
    tap(locale["settings"][0])
    time.sleep(1)
    (folder / "8_settings.png").write_bytes(settled())
    log(f"{locale['language']}: 8_settings")


def capture_scan(locale, folder):
    search, scan, again, _ = locale["scan"]
    folder.mkdir(parents=True, exist_ok=True)
    demo_mode()
    tap(search)
    tap(scan)
    started = time.monotonic()
    while not find(again)(nodes()):
        if time.monotonic() - started > 180:
            raise TimeoutError(again)
        if time.monotonic() - started > 30:
            shell("input", "tap", "540", "800")
        time.sleep(2)
    time.sleep(4)
    (folder / "7_scan.png").write_bytes(settled())
    log(f"{locale['language']}: 7_scan")


def demo_mode():
    shell("settings", "put", "global", "sysui_demo_allowed", "1")
    shell(*DEMO, "enter")
    shell(*DEMO, "clock", "-e", "hhmm", "1230")
    shell(*DEMO, "battery", "-e", "level", "100", "-e", "plugged", "false")
    shell(*DEMO, "network", "-e", "wifi", "show", "-e", "level", "4",
          "-e", "fully", "true")
    shell(*DEMO, "network", "-e", "mobile", "hide")
    shell(*DEMO, "notifications", "-e", "visible", "false")


def cover_image(cover):
    path = CACHE / f"cover-{cover}.jpg"
    if not path.exists():
        url = f"https://covers.openlibrary.org/b/id/{cover}-L.jpg"
        urllib.request.urlretrieve(url, path)
    return Image.open(path).convert("RGB")


def wood():
    width, height = SCENE_SIZE
    random = numpy.random.default_rng(7)
    streaks = Image.fromarray(random.normal(128, 40, (height // 60, width)).clip(0, 255)
                              .astype("uint8")).resize(SCENE_SIZE, Image.BICUBIC)
    grain = numpy.asarray(streaks) / 255 - 0.5
    plank = numpy.arange(width) // 360
    tint = random.uniform(-0.06, 0.06, plank.max() + 1)[plank]
    y, x = numpy.mgrid[0:height, 0:width] / height
    light = 1.12 - 0.3 * ((x - 0.37) ** 2 + (y - 0.3) ** 2)
    seam = numpy.where(numpy.arange(width) % 360 < 3, 0.55, 1.0)
    shade = (0.95 + 0.22 * grain + tint) * light * seam
    color = numpy.array([178, 128, 84]) * shade[..., None]
    return Image.fromarray(color.clip(0, 255).astype("uint8"))


def book(cover):
    image = cover_image(cover)
    width = round(BOOK_HEIGHT * image.width / image.height)
    front = image.resize((width, BOOK_HEIGHT), Image.LANCZOS)
    sheet = Image.new("RGBA", (width + PAGES, BOOK_HEIGHT + PAGES))
    draw = ImageDraw.Draw(sheet)
    draw.rectangle((PAGES, PAGES, width + PAGES, BOOK_HEIGHT + PAGES), fill="#E9E1D0")
    for offset in range(4, PAGES, 4):
        draw.line((width + offset, offset, width + offset, BOOK_HEIGHT + offset),
                  fill="#CFC5B0")
        draw.line((offset, BOOK_HEIGHT + offset, width + offset, BOOK_HEIGHT + offset),
                  fill="#CFC5B0")
    sheet.paste(front, (0, 0))
    return sheet


def shadow_of(volume):
    shadow = Image.new("RGBA", (volume.width + 160, volume.height + 160), (25, 12, 0, 0))
    mask = Image.new("L", shadow.size)
    mask.paste(volume.getchannel("A").point(lambda alpha: alpha * 0.6), (80, 80))
    shadow.putalpha(mask)
    return shadow


def scene_frame(table, volume, shadow, step):
    phase = 2 * math.pi * step / VIDEO_FRAMES
    angle = 4 + 2 * math.sin(phase)
    scale = 1 + 0.03 * math.sin(2 * phase)
    drift = (round(12 * math.cos(phase)), round(12 * math.sin(phase)))
    scene = table.copy()
    for layer, offset in ((shadow, (30, 40)), (volume, (0, 0))):
        size = (round(layer.width * scale), round(layer.height * scale))
        turned = layer.resize(size, Image.LANCZOS)
        turned = turned.rotate(angle, Image.BICUBIC, expand=True)
        if layer is shadow:
            turned = turned.filter(ImageFilter.GaussianBlur(28))
        left = (SCENE_SIZE[0] - turned.width) // 2 + offset[0] + drift[0]
        top = BOOK_CENTER - turned.height // 2 + offset[1] + drift[1]
        scene.alpha_composite(turned, (left, top))
    width, height = SCENE_SIZE
    camera = Image.new("RGB", (width * 16 // 9, height * 16 // 9 // 2 * 2), "#9A6B45")
    camera.paste(scene.convert("RGB"), (0, (camera.height - height) // 2))
    return camera


def render_scene(cover):
    folder = CACHE / "scene"
    shutil.rmtree(folder, ignore_errors=True)
    folder.mkdir()
    table = wood().convert("RGBA")
    volume = book(cover)
    shadow = shadow_of(volume)
    for step in range(VIDEO_FRAMES):
        frame = scene_frame(table, volume, shadow, step)
        frame.save(folder / f"{step:03d}.jpg", quality=92)
    path = CACHE / "scene.mp4"
    subprocess.run(["ffmpeg", "-y", "-loglevel", "error", "-framerate", "10", "-i",
                    folder / "%03d.jpg", "-c:v", "libx264", "-pix_fmt", "yuv420p", path],
                   check=True)
    return path


def restart_emulator(camera):
    if adb("get-state").strip() == b"device":
        shell("sync")
        adb("emu", "kill")
    while adb("get-state").strip() == b"device":
        time.sleep(1)
    command = [EMULATOR / "emulator", "-avd", AVD, "-no-snapshot", "-no-audio",
               "-camera-back", f"videofile:{camera}"]
    subprocess.Popen(command, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                     start_new_session=True)
    adb("wait-for-device", timeout=300)
    while shell("getprop", "init.svc.bootanim").strip() != "stopped":
        time.sleep(1)
    prepare_device()


def prepare_device():
    adb("root")
    adb("wait-for-device")
    for scale in ("window_animation_scale", "transition_animation_scale",
                  "animator_duration_scale"):
        shell("settings", "put", "global", scale, "0")
    demo_mode()


def raw_folder(language, theme):
    return CACHE / "raw" / f"{language}-{theme}"


ONLY = {"7_scan": capture_scan, "8_settings": capture_settings}


def capture_all(plan, only):
    prepare_device()
    try:
        for language, themes in plan:
            locale = LOCALES[language] | dict(language=language)
            if only in (None, "7_scan"):
                restart_emulator(render_scene(locale["scan"][3]))
            for theme in themes:
                log(f"{language}: {theme}")
                seed(locale, theme)
                launch()
                download_packs(locale)
                launch()
                ONLY.get(only, capture)(locale, raw_folder(language, theme))
    finally:
        shell("am", "force-stop", PACKAGE)
        shell(*DEMO, "exit")


def output_folder(locale, theme):
    if theme == "Dark":
        return DARK
    return FASTLANE / locale["folder"] / "images/phoneScreenshots"


def without_band(screen):
    top, bottom = CUT
    joined = Image.new(screen.mode, (screen.width, screen.height - bottom + top))
    joined.paste(screen.crop((0, 0, screen.width, top)))
    joined.paste(screen.crop((0, bottom, screen.width, screen.height)), (0, top))
    return joined


def compose_all(plan, only):
    for language, themes in plan:
        locale = LOCALES[language]
        size = tiles.headline_size([headline for headline, _ in locale["copy"]])
        for theme in themes:
            target = output_folder(locale, theme)
            target.mkdir(parents=True, exist_ok=True)
            screens = list(enumerate(zip(SCREENS, locale["copy"])))
            screens = [screen for screen in screens if only in (None, screen[1][0])]
            for index, (name, (headline, detail)) in screens:
                screen = Image.open(raw_folder(language, theme) / f"{name}.png")
                if name == "7_scan":
                    screen = without_band(screen)
                dark = theme == "Dark"
                tile = tiles.compose(screen, index, headline, detail, size, dark)
                tile.save(target / f"{name}.jpg", quality=90, optimize=True)
            log(f"{language}: {theme} → {target.relative_to(ROOT)}")


def variants(languages, dark_only):
    if dark_only:
        return [("en", ["Dark"])]
    return [(language, ["Light", "Dark"] if language == "en" else ["Light"])
            for language in languages]


def main():
    parser = argparse.ArgumentParser(description="Capture and compose store screenshots")
    parser.add_argument("--locales", nargs="+", choices=LOCALES, default=list(LOCALES))
    parser.add_argument("--dark", action="store_true", help="only the dark English set")
    parser.add_argument("--compose-only", action="store_true",
                        help="reuse cached captures without the emulator")
    parser.add_argument("--only", choices=ONLY, help="just this screen")
    arguments = parser.parse_args()
    CACHE.mkdir(exist_ok=True)
    plan = variants(arguments.locales, arguments.dark)
    if not arguments.compose_only:
        capture_all(plan, arguments.only)
    compose_all(plan, arguments.only)


if __name__ == "__main__":
    main()
