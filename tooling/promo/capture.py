#!/usr/bin/env python3
import subprocess
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent.parent))
import screenshots as app

CLIPS = Path(__file__).resolve().parent / "out" / "clips"
PACKS = app.catalogue_packs(["en"])
FOUND = app.catalogue_books(["en"], PACKS)["en"]
BOOKS = app.shelves("en", FOUND)
PHONE = ("1080x2424", "420")
TABLET = ("1264x1680", "340")
DEMO = ["am", "broadcast", "-a", "com.android.systemui.demo", "-e", "command"]
READER = BOOKS["reader"][1]


def seed(theme):
    app.seed("en", BOOKS, FOUND["chapters"], theme, PACKS)


def status_bar():
    app.shell(*DEMO, "exit")
    time.sleep(1)
    app.shell(*DEMO, "enter")
    app.shell(*DEMO, "clock", "-e", "hhmm", "0941")
    app.shell(*DEMO, "battery", "-e", "level", "100", "-e", "plugged", "false")
    app.shell(*DEMO, "network", "-e", "wifi", "show", "-e", "level", "4",
              "-e", "fully", "true")
    app.shell(*DEMO, "network", "-e", "mobile", "hide")
    app.shell(*DEMO, "notifications", "-e", "visible", "false")


def screen(profile):
    size, density = profile
    app.shell("wm", "size", size)
    app.shell("wm", "density", density)
    time.sleep(2)
    status_bar()


def swipe(direction, length=900, duration=900):
    start = 1900 if direction == "up" else 1000
    end = start - length if direction == "up" else start + length
    app.shell("input", "swipe", "540", str(start), "540", str(end), str(duration))


def record(name, seconds, actions, size=PHONE[0]):
    command = ["adb", "-s", app.SERIAL, "shell", "screenrecord",
               "--time-limit", str(seconds), "--size", size,
               "--bit-rate", "30000000", "/sdcard/clip.mp4"]
    process = subprocess.Popen(command)
    time.sleep(1.2)
    actions()
    process.wait()
    CLIPS.mkdir(parents=True, exist_ok=True)
    app.adb("pull", "/sdcard/clip.mp4", str(CLIPS / f"{name}.mp4"))
    app.log(name)


def home():
    app.launch()
    app.wait(app.find(READER))
    app.settled()


def scroll_home():
    time.sleep(1.2)
    for direction in ("up", "up", "down", "down"):
        swipe(direction, 800)
        time.sleep(0.6)


def search():
    app.tap("Search")
    time.sleep(1.2)
    app.tap("Books and authors")
    time.sleep(1.0)
    for letter in "hobit":
        app.shell("input", "text", letter)
        time.sleep(0.35)
    time.sleep(2.0)


def book():
    app.tap("Library")
    title, author = BOOKS["reading"][0][1:3]
    app.tap(title)
    app.wait(app.find(author))
    time.sleep(1.2)
    swipe("up")
    time.sleep(0.8)
    swipe("up")


def listen():
    time.sleep(1.0)
    app.tap(READER)
    time.sleep(2.4)
    app.tap("Forward 10 seconds")
    time.sleep(0.8)
    app.tap("Forward 10 seconds")


def wrapped():
    app.tap("Reading stats")
    time.sleep(1.0)
    app.tap(app.fragment("en", "wrapped_banner"), exact=False)
    time.sleep(2.6)
    for _ in range(3):
        app.shell("input", "tap", "900", "1200")
        time.sleep(1.6)


def explore():
    app.tap("Explore")
    time.sleep(1.5)
    for direction, length in (("up", 900), ("up", 900), ("down", 1200)):
        swipe(direction, length)
        time.sleep(0.8)


def read_and_dim():
    card = app.center(app.wait(app.find(READER))[0])

    def actions():
        app.shell("input", "tap", *card)
        time.sleep(1.4)
        app.back()
        time.sleep(1.0)
        for _ in range(3):
            app.shell("input", "swipe", "1050", "900", "200", "900", "200")
            time.sleep(1.5)
        time.sleep(1.0)
        app.shell("cmd", "uimode", "night", "yes")
        time.sleep(2.0)
        for _ in range(3):
            app.shell("input", "swipe", "1050", "900", "200", "900", "200")
            time.sleep(1.5)

    return actions


LIGHT_CLIPS = [("home", 11, scroll_home), ("search", 14, search), ("book", 14, book),
               ("explore", 10, explore)]
DARK_CLIPS = [("listen", 9, listen), ("wrapped", 13, wrapped)]


def phone_clips(wanted):
    screen(PHONE)
    for theme, clips in (("Light", LIGHT_CLIPS), ("Dark", DARK_CLIPS)):
        chosen = [clip for clip in clips if clip[0] in wanted]
        if not chosen:
            continue
        seed(theme)
        for name, seconds, actions in chosen:
            home()
            record(name, seconds, actions)


def tablet_clip():
    screen(TABLET)
    app.shell("cmd", "uimode", "night", "no")
    seed("System")
    app.launch()
    time.sleep(4)
    record("tablet_take", 16, read_and_dim(), TABLET[0])
    app.shell("cmd", "uimode", "night", "no")
    screen(PHONE)


if __name__ == "__main__":
    CLIPS.mkdir(parents=True, exist_ok=True)
    every = [clip[0] for clip in LIGHT_CLIPS + DARK_CLIPS] + ["tablet_take"]
    wanted = sys.argv[1:] or every
    app.start_emulator()
    phone_clips(wanted)
    if "tablet_take" in wanted:
        tablet_clip()
    app.shell("am", "force-stop", app.PACKAGE)
