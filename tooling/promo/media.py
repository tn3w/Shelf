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

PHONE_LAYERS = [
    ("home.mp4", 0.5, 0.6, 0.0, 12.0, 0),
    ("home.mp4", 0.0, 8.1, 3.6, 12.0, 0),
    ("search.mp4", 2.0, 9.4, 12.0, 22.0, FADE),
    ("book.mp4", 2.5, 8.4, 22.0, 27.9, FADE),
    ("explore.mp4", 3.0, 6.4, 27.9, 45.5, FADE),
    ("dark_home_scroll.mp4", 0.0, 8.1, 45.5, DURATION, FADE),
]

TABLET_LAYERS = [
    ("tablet_take.mp4", 0.8, 0.9, 0.0, 31.0, 0),
    ("tablet_take.mp4", 0.6, 9.6, 31.0, 41.0, FADE),
    ("tablet_take.mp4", 9.9, 15.7, 40.0, DURATION, 0.8),
]


def layer_filter(index, layer):
    _, start, end, at, until, fade = layer
    chain = (
        f"[{index}:v]trim={start}:{end},setpts=PTS-STARTPTS+{at}/TB,fps={FPS},"
        f"tpad=stop_mode=clone:stop_duration={until}"
    )
    if fade:
        chain += f",format=yuva420p,fade=t=in:st={at}:d={fade}:alpha=1"
    return chain + f"[layer{index}]"


def master(name, size, layers):
    width, height = size
    inputs = [argument for layer in layers for argument in ("-i", CLIPS / layer[0])]
    filters = [layer_filter(index, layer) for index, layer in enumerate(layers)]
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
