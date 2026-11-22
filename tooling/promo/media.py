#!/usr/bin/env python3
import os
import subprocess
import sys
import urllib.request
from pathlib import Path

OUT = Path(__file__).resolve().parent / "out"
CLIPS = OUT / "clips"
FPS = int(os.environ.get("PROMO_FPS", 30))
FRAMES = os.environ.get("PROMO_FRAMES", "frames")
EXTENSION = os.environ.get("PROMO_FORMAT", "png")
SUFFIX = os.environ.get("PROMO_SUFFIX", "")
SOFTWARE = ["-c:v", "libx264", "-preset", "slow", "-crf", "15"]
HARDWARE = ["-c:v", "h264_nvenc", "-preset", "p7", "-tune", "hq", "-rc", "vbr", "-cq", "16",
            "-b:v", "0", "-maxrate", "90M", "-bufsize", "180M"]
ENCODER = HARDWARE if os.environ.get("PROMO_NVENC") else SOFTWARE
DURATION = 58
FADE = 0.4

PHONE_SIZE = (1080, 2424)
TABLET_SIZE = (1264, 1680)

SCREEN = float(os.environ.get("PROMO_SCREEN", 1))

PHONE_LAYERS = [
    ("home.mp4", 0.5, 0.6, 0.0, 12.3, 0),
    ("home.mp4", 0.0, 8.0, 3.6, 12.3, 0),
    ("search.mp4", 3.4, 13.5, 12.3, 23.35, 0),
    ("book.mp4", 7.85, 11.0, 23.35, 26.45, 0),
    ("explore.mp4", 3.4, 9.5, 26.2, 45.0, 0.6),
    ("listen.mp4", 3.6, 8.8, 45.0, 49.3, 0),
    ("wrapped.mp4", 5.0, 12.2, 49.3, DURATION, 0.5),
]

TABLET_LAYERS = [
    ("tablet_take.mp4", 2.7, 2.75, 0.0, 31.0, 0),
    ("tablet_take.mp4", 2.7, 9.9, 31.0, 39.6, 0),
    ("tablet_take.mp4", 9.6, 15.9, 39.6, DURATION, 0),
]


def layer_filter(index, layer, width, height):
    _, start, end, at, until, fade = layer
    chain = (
        f"[{index}:v]trim={start}:{end},setpts=PTS-STARTPTS+{at}/TB,fps={FPS},"
        f"scale={width}:{height},"
        f"tpad=stop_mode=clone:stop_duration={until}"
    )
    if fade:
        chain += f",format=yuva420p,fade=t=in:st={at}:d={fade}:alpha=1"
    return chain + f"[layer{index}]"


def master(name, size, layers):
    width, height = (round(side * SCREEN / 2) * 2 for side in size)
    inputs = [argument for layer in layers for argument in ("-i", CLIPS / layer[0])]
    filters = [layer_filter(index, layer, width, height)
               for index, layer in enumerate(layers)]
    filters.append(
        f"color=c=black:s={width}x{height}:r={FPS}:d={DURATION},format=yuv420p[base0]"
    )
    for index, layer in enumerate(layers):
        filters.append(
            f"[base{index}][layer{index}]overlay=format=auto:eof_action=pass:"
            f"enable='gte(t,{layer[3]})'[base{index + 1}]"
        )
    filters.append(f"[base{len(layers)}]scale={width}:{height},trim=duration={DURATION}[out]")
    command = [
        "ffmpeg", "-v", "error", "-y", *inputs,
        "-filter_complex", ";".join(filters), "-map", "[out]",
        "-c:v", "libx264", "-crf", "12", "-g", "1", "-pix_fmt", "yuv420p",
        OUT / f"{name}{SUFFIX}.mp4",
    ]
    subprocess.run(command, check=True)


COVERS = {
    "hobbit": 14627509, "dune": 11481354, "circe": 8739376,
    "nineteen": 9267242, "prince": 10708272, "library": 10313767,
}


def covers():
    target = OUT / "covers"
    target.mkdir(exist_ok=True)
    for name, identifier in COVERS.items():
        url = f"https://covers.openlibrary.org/b/id/{identifier}-L.jpg"
        request = urllib.request.Request(url, headers={"User-Agent": "Shelf"})
        with urllib.request.urlopen(request) as response:
            (target / f"{name}.jpg").write_bytes(response.read())


def encode(target):
    command = [
        "ffmpeg", "-v", "error", "-y", "-framerate", str(FPS),
        "-i", OUT / FRAMES / f"%05d.{EXTENSION}", "-i", OUT / "music.wav",
        "-vf", f"fade=t=in:d=0.5,fade=t=out:st={DURATION - 0.6}:d=0.6,format=yuv420p",
        *ENCODER, "-movflags", "+faststart",
        "-c:a", "aac", "-b:a", "256k", "-shortest", target,
    ]
    subprocess.run(command, check=True)


if __name__ == "__main__":
    if sys.argv[1:] == ["encode"]:
        encode(OUT / f"shelf{SUFFIX}.mp4")
    elif sys.argv[1:] == ["covers"]:
        covers()
    else:
        master("phone", PHONE_SIZE, PHONE_LAYERS)
        master("tablet", TABLET_SIZE, TABLET_LAYERS)
