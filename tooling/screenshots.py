#!/usr/bin/env python3
import datetime
import json
import re
import subprocess
import sys
import tempfile
import time
import urllib.request
from pathlib import Path

PACKAGE = "dev.tn3w.shelf"
DATA = f"/data/data/{PACKAGE}/files"
ROOT = Path(__file__).resolve().parent.parent
FASTLANE = ROOT / "android/fastlane/metadata/android"
DARK = ROOT / "android/design/screenshots"
CACHE = Path(tempfile.gettempdir()) / "shelf-screenshots"
DAY = 86_400_000
PALETTE = "FF3C6E71"

LOCALES = {
    "en": dict(
        folder="en-US",
        tabs=("Home", "Library", "Explore"),
        settings=("Settings", "All ("),
        cover="Cover of",
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
                    dailyGoal=20, onlineCovers=True, checkUpdates=False,
                    lastCatalogueCheck=now, lastAppCheck=now)
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
        time.sleep(0.5)
    raise TimeoutError(condition.__doc__ or "screen")


def find(label, exact=True):
    def condition(current):
        return [node for node in current
                if (label in node[:2] if exact else label in node[0] + node[1])]
    condition.__doc__ = label
    return condition


def tap(label, exact=True, timeout=30):
    shell("input", "tap", *center(wait(find(label, exact), timeout)[0]))


def covers(locale, minimum):
    def condition(current):
        found = [node for node in current if node[1].startswith(locale["cover"])]
        return found if len(found) >= minimum else []
    condition.__doc__ = f"{minimum} covers"
    return condition


def settled(timeout=20):
    previous = adb("exec-out", "screencap", "-p")
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        time.sleep(1.5)
        current = adb("exec-out", "screencap", "-p")
        if current == previous:
            return current
        previous = current
    return previous


def log(message):
    print(time.strftime("%H:%M:%S"), message, flush=True)


def save(image, target):
    target.parent.mkdir(parents=True, exist_ok=True)
    subprocess.run(
        ["magick", "png:-", "-resize", "720x", "-strip", "-sampling-factor", "4:2:0",
         "-quality", "84", str(target)],
        input=image, check=True,
    )
    log(f"{target.relative_to(ROOT)} {target.stat().st_size // 1024} KB")


def download_packs(locale):
    title, button = locale["settings"]
    tap(title)
    wait(find(locale["tabs"][0]))
    if not find(button, exact=False)(nodes()):
        return
    tap(button, exact=False)
    wait(lambda current: not find(button, exact=False)(current), timeout=900)


def center(node):
    _, _, left, top, right, bottom = node
    return str((int(left) + int(right)) // 2), str((int(top) + int(bottom)) // 2)


def show_series(locale):
    shell("input", "swipe", "540", "1900", "540", "900", "400")
    description = max(nodes(), key=lambda node: len(node[0]))
    if len(description[0]) > 150:
        shell("input", "tap", *center(description))
    for _ in range(15):
        found = covers(locale, 3)(nodes())
        if found and int(found[0][5]) < 2000:
            return
        shell("input", "swipe", "540", "1600", "540", "1100", "400")
    raise TimeoutError("series row")


def text_height(image):
    result = subprocess.run(
        ["magick", "png:-", "-crop", "1080x2000+0+200", "-fuzz", "20%", "-trim",
         "-format", "%h", "info:"],
        input=image, capture_output=True, check=True,
    )
    return int(result.stdout)


def full_page():
    for _ in range(20):
        image = settled()
        if text_height(image) > 1600:
            return image
        shell("input", "tap", "1000", "1200")
    raise TimeoutError("full reader page")


def capture(locale, folder):
    home, library_tab, explore = locale["tabs"]
    reader = locale["reader"]
    book = locale["reading"][0]

    wait(find(reader[1]))
    wait(covers(locale, 3))
    save(settled(), folder / "1_home.jpg")

    tap(library_tab)
    total = 1 + sum(len(locale[shelf]) for shelf in ("reading", "want", "finished"))
    wait(covers(locale, min(9, total)))
    save(settled(), folder / "2_library.jpg")

    tap(book[1])
    wait(find(book[2]))
    save(settled(), folder / "3_book.jpg")

    show_series(locale)
    save(settled(), folder / "4_series.jpg")

    tap(explore)
    wait(covers(locale, 3))
    save(settled(), folder / "5_explore.jpg")

    tap(home)
    tap(reader[1])
    wait(lambda current: not find(home)(current))
    save(full_page(), folder / "6_reader.jpg")


def prepare_device():
    adb("root")
    adb("wait-for-device")
    for scale in ("window_animation_scale", "transition_animation_scale",
                  "animator_duration_scale"):
        shell("settings", "put", "global", scale, "0")
    palette = json.dumps({
        "android.theme.customization.color_source": "preset",
        "android.theme.customization.system_palette": PALETTE,
        "android.theme.customization.accent_color": PALETTE,
        "android.theme.customization.theme_style": "TONAL_SPOT",
    })
    shell("settings", "put", "secure", "theme_customization_overlay_packages",
          f"'{palette}'")
    time.sleep(5)
    shell("settings", "put", "global", "sysui_demo_allowed", "1")
    demo = ["am", "broadcast", "-a", "com.android.systemui.demo", "-e", "command"]
    shell(*demo, "enter")
    shell(*demo, "clock", "-e", "hhmm", "0941")
    shell(*demo, "battery", "-e", "level", "100", "-e", "plugged", "false")
    shell(*demo, "network", "-e", "wifi", "show", "-e", "level", "4",
          "-e", "fully", "true")
    shell(*demo, "notifications", "-e", "visible", "false")
    launch()
    time.sleep(3)


def main(languages):
    CACHE.mkdir(exist_ok=True)
    prepare_device()
    for language in languages:
        locale = LOCALES[language] | dict(language=language)
        log(f"{language}: seed")
        seed(locale, "Light")
        launch()
        log(f"{language}: packs")
        download_packs(locale)
        launch()
        capture(locale, FASTLANE / locale["folder"] / "images/phoneScreenshots")
        if language == "en":
            seed(locale, "Dark")
            launch()
            capture(locale, DARK / "dark")
    shell("am", "force-stop", PACKAGE)


if __name__ == "__main__":
    main(sys.argv[1:] or list(LOCALES))
