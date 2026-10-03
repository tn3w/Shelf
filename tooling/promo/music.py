#!/usr/bin/env python3
import wave
from functools import lru_cache
from pathlib import Path

import numpy as np

OUT = Path(__file__).resolve().parent / "out"
RATE = 44100
DURATION = 58.0
BEAT = 0.5
BAR = 4 * BEAT
BUSES = ("kicks", "drums", "bass", "pads", "stabs", "arps", "lead", "bells", "fx", "hits")

CHORDS = [
    dict(root=38, pad=[50, 54, 57, 62, 66], tones=[62, 66, 69, 74, 78, 81]),
    dict(root=33, pad=[45, 52, 57, 61, 64], tones=[57, 61, 64, 69, 73, 76]),
    dict(root=35, pad=[47, 54, 59, 62, 66], tones=[59, 62, 66, 71, 74, 78]),
    dict(root=31, pad=[43, 50, 55, 59, 62], tones=[55, 59, 62, 67, 71, 74]),
]
MELODY = [
    [(0, 78, 1.5), (1.5, 76, 0.5), (2, 74, 1), (3, 76, 1)],
    [(0, 73, 1.5), (1.5, 74, 0.5), (2, 76, 2)],
    [(0, 74, 1.5), (1.5, 71, 0.5), (2, 74, 1), (3, 78, 1)],
    [(0, 76, 1), (1, 74, 1), (2, 71, 2)],
]
ARPEGGIO = [0, 1, 2, 3, 2, 1, 2, 4]
STAR_NOTES = [86, 88, 90, 93, 95, 93, 90, 88]
INTENSITY = [
    (0, 0.7), (2, 0.65), (4, 0.72), (12, 0.85), (20, 0.95), (21.9, 0.97), (22.0, 1.0),
    (38.4, 1.0), (39.2, 0.88), (39.8, 0.62), (40.0, 0.55), (41, 0.55), (46.0, 0.55), (46.8, 1.0), (52.4, 1.0),
    (54, 0.7), (58, 0.5),
]
BUILDS = [(19.0, 22.0), (26.4, 29.8), (37.0, 40.0), (48.4, 52.4)]
CLOSING = [(38.4, 0.0), (40.0, 5.0), (40.8, 5.0), (41.8, 0.0)]
CUTOFFS = (20000, 9000, 4000, 1800, 800, 350)

rng = np.random.default_rng(7)


def frequency(note):
    return 440.0 * 2 ** ((note - 69) / 12)


def seconds(length):
    return np.arange(int(length * RATE)) / RATE


def band(signal, low=None, high=None, order=4):
    size = signal.shape[-1]
    frequencies = np.fft.rfftfreq(size, 1 / RATE)
    gain = np.ones_like(frequencies)
    if low:
        gain /= np.sqrt(1 + (low / np.maximum(frequencies, 1e-3)) ** (2 * order))
    if high:
        gain /= np.sqrt(1 + (frequencies / high) ** (2 * order))
    return np.fft.irfft(np.fft.rfft(signal, axis=-1) * gain, size, axis=-1)


def taper(signal, fade_in=0.002):
    head = min(int(fade_in * RATE), len(signal))
    tail = int(np.clip(len(signal) * 0.15, 0.008 * RATE, 0.12 * RATE))
    tail = min(tail, len(signal))
    shaped = signal.copy()
    shaped[:head] *= np.linspace(0, 1, head)
    shaped[len(shaped) - tail :] *= np.cos(np.linspace(0, np.pi / 2, tail)) ** 2
    return shaped


class Mix:
    def __init__(self):
        size = int(DURATION * RATE)
        self.tracks = {name: np.zeros((2, size)) for name in BUSES}

    def add(self, bus, signal, start, pan=0.0, gain=1.0):
        track = self.tracks[bus]
        first = int(start * RATE)
        if first >= track.shape[1] or first + len(signal) <= 0:
            return
        signal = taper(signal)
        left = max(-first, 0)
        signal = signal[left:]
        last = min(first + left + len(signal), track.shape[1])
        signal = signal[: last - first - left]
        angle = (pan + 1) * np.pi / 4
        track[0, first + left : last] += signal * np.cos(angle) * gain
        track[1, first + left : last] += signal * np.sin(angle) * gain


def pluck(note, length=2.2, bright=1.0):
    time = seconds(length)
    base = frequency(note)
    decay = 1.6 / (1 + (note - 60) / 30)
    signal = np.zeros_like(time)
    for partial in range(1, 11):
        stretched = base * partial * np.sqrt(1 + 0.0004 * partial**2)
        if stretched > 9000:
            break
        weight = bright / partial**1.15
        signal += weight * np.sin(2 * np.pi * stretched * time) * np.exp(
            -time / (decay / (1 + 0.5 * (partial - 1)))
        )
    attack = np.minimum(time / 0.004, 1.0)
    return signal * attack * 0.35


def bell(note, length=4.0):
    time = seconds(length)
    base = frequency(note)
    signal = np.zeros_like(time)
    for ratio, weight, decay in ((1, 1.0, 2.5), (2.76, 0.4, 1.4), (5.4, 0.2, 0.7)):
        signal += weight * np.sin(2 * np.pi * base * ratio * time) * np.exp(-time / decay)
    return signal * np.minimum(time / 0.003, 1.0) * 0.25


def saw_voice(note, length, detunes, top, release=0.12):
    time = seconds(length + release)
    base = frequency(note)
    signal = np.zeros_like(time)
    for index, cents in enumerate(detunes):
        detuned = base * 2 ** (cents / 1200)
        for partial in range(1, int(top / detuned) + 1):
            signal += np.sin(2 * np.pi * detuned * partial * time + index * partial) / partial
    attack = np.minimum(time / 0.008, 1.0)
    cut = np.where(time > length, np.exp(-(time - length) / (release / 4)), 1.0)
    return signal * attack * cut / len(detunes)


def pad_note(note, length, attack=1.2, release=1.6):
    time = seconds(length + release)
    base = frequency(note)
    signal = np.zeros_like(time)
    for index, cents in enumerate((-7, 0, 7)):
        detuned = base * 2 ** (cents / 1200)
        for partial in range(1, int(3200 / detuned) + 1):
            signal += np.sin(2 * np.pi * detuned * partial * time + index * partial) / partial**1.2
    envelope = np.minimum(time / attack, 1.0)
    envelope *= np.where(time > length, np.exp(-(time - length) / (release / 4)), 1.0)
    return signal * envelope * 0.05


@lru_cache(maxsize=None)
def stab(chord_index):
    chord = np.zeros(int(0.5 * RATE))
    for note in CHORDS[chord_index]["pad"][1:]:
        voice = saw_voice(note + 12, 0.16, (-12, -5, 0, 5, 12), 6000)
        chord[: len(voice)] += voice[: len(chord)]
    return chord * np.exp(-seconds(0.5) / 0.16) * 0.4


@lru_cache(maxsize=None)
def lead_note(note, length):
    voice = saw_voice(note, length, (-8, 0, 8), 4800, 0.2)
    time = seconds(length + 0.2)
    sub = np.sin(np.pi * frequency(note) * time) * 0.25
    return (voice + sub) * 0.35 * np.exp(-time / (length * 3))


@lru_cache(maxsize=None)
def bass_hard(note, length):
    voice = band(saw_voice(note, length, (-3, 0, 3), 900, 0.05), None, 700)
    time = seconds(length + 0.05)
    sub = np.sin(2 * np.pi * frequency(note) * time)
    envelope = np.minimum(time / 0.004, 1.0) * np.exp(-time / (length * 1.5))
    return np.tanh((voice * 0.35 + sub) * envelope * 0.9) * 0.6


def bass_note(note, length):
    time = seconds(length)
    base = frequency(note)
    signal = np.sin(2 * np.pi * base * time) + 0.25 * np.sin(4 * np.pi * base * time)
    envelope = np.minimum(time / 0.01, 1.0) * np.exp(-time / (length * 1.2))
    return signal * envelope * 0.55


@lru_cache(maxsize=None)
def kick(hard=False):
    time = seconds(0.55 if hard else 0.5)
    sweep, tail = (110, 0.28) if hard else (70, 0.22)
    phase = 2 * np.pi * (46 * time + sweep * 0.028 * (1 - np.exp(-time / 0.028)))
    body = np.sin(phase) * np.exp(-time / tail)
    click = band(rng.standard_normal(len(time)), 1800, 9000) * np.exp(-time / 0.003)
    return np.tanh((body + click * 0.25) * 1.15) * (0.95 if hard else 0.85)


def normalized(signal, peak):
    return signal / np.max(np.abs(signal)) * peak


@lru_cache(maxsize=None)
def clap():
    signal = np.zeros(int(0.35 * RATE))
    for offset, decay in ((0, 0.012), (0.011, 0.012), (0.023, 0.07)):
        burst = band(rng.standard_normal(int(0.3 * RATE)), 900, 6500, 2)
        burst *= np.exp(-seconds(0.3) / decay)
        start = int(offset * RATE)
        signal[start : start + len(burst)] += burst
    return normalized(signal, 0.5)


@lru_cache(maxsize=None)
def snare():
    time = seconds(0.35)
    tone = np.sin(2 * np.pi * 190 * time * (1 + 0.25 * np.exp(-time / 0.02)))
    tone *= np.exp(-time / 0.07)
    noise = band(rng.standard_normal(len(time)), 1500, 9000, 2) * np.exp(-time / 0.09)
    return normalized(tone * 0.9 + normalized(noise, 0.55), 0.7)


@lru_cache(maxsize=None)
def hat(open_hat=False):
    time = seconds(0.4 if open_hat else 0.1)
    noise = band(rng.standard_normal(len(time)), 7500, 15000, 3)
    return normalized(noise * np.exp(-time / (0.11 if open_hat else 0.02)), 0.3)


def sweep(length, start_hz, end_hz, width=1.2, rising=True):
    size = 1024
    hop = size // 4
    count = int(length * RATE / hop)
    window = np.hanning(size)
    output = np.zeros(count * hop + size)
    frequencies = np.fft.rfftfreq(size, 1 / RATE)
    for frame in range(count):
        progress = frame / max(count - 1, 1)
        center = start_hz * (end_hz / start_hz) ** progress
        mask = np.exp(-0.5 * (np.log2(np.maximum(frequencies, 20) / center) / width) ** 2)
        spectrum = (rng.standard_normal(len(frequencies))
                    + 1j * rng.standard_normal(len(frequencies))) * mask
        output[frame * hop : frame * hop + size] += np.fft.irfft(spectrum) * window
    output = output[: int(length * RATE)]
    shape = np.linspace(0, 1, len(output))
    envelope = shape**2 if rising else (1 - shape) ** 1.5
    return normalized(output, 0.5) * envelope * np.minimum(shape / 0.05, 1)


def impact(length=3.0):
    time = seconds(length)
    phase = 2 * np.pi * (30 * time + 55 * 0.15 * (1 - np.exp(-time / 0.15)))
    boom = np.sin(phase) * np.exp(-time / 1.0)
    air = band(rng.standard_normal(len(time)), 3000, 12000, 2) * np.exp(-time / 0.6)
    return np.tanh(boom * 1.1 + air * 0.03) * 0.85


def convolve(signal, impulse):
    size = 1 << int(np.ceil(np.log2(signal.shape[1] + impulse.shape[1])))
    spectrum = np.fft.rfft(signal, size) * np.fft.rfft(impulse, size)
    return np.fft.irfft(spectrum, size)[:, : signal.shape[1]]


def reverb_impulse(length=2.4, decay=0.55, predelay=0.022):
    time = seconds(length)
    impulse = rng.standard_normal((2, len(time))) * np.exp(-time / decay)
    impulse *= np.minimum(time / 0.012, 1.0)
    impulse = band(impulse, 180, 6000, 2)
    return np.pad(impulse, ((0, 0), (int(predelay * RATE), 0))) * 0.045


def echo(signal, delay=0.375, repeats=4, decay=0.5):
    output = np.zeros_like(signal)
    for repeat in range(1, repeats + 1):
        shift = int(delay * RATE * repeat)
        copy = signal[::-1] if repeat % 2 else signal
        output[:, shift:] += copy[:, :-shift] * decay**repeat
    return band(output, 220, 5000, 2)


def ducking(kicks, depth=0.6, release=0.18):
    gain = np.ones(int(DURATION * RATE))
    size = int(release * RATE * 3)
    curve = 1 - depth * np.exp(-seconds(release * 3) / (release / 2.2))
    for moment in kicks:
        start = int(moment * RATE)
        end = min(start + size, len(gain))
        gain[start:end] = np.minimum(gain[start:end], curve[: end - start])
    return gain


def bars(first, last):
    return [bar * BAR for bar in range(int(first / BAR), int(last / BAR))]


def chord_index(moment):
    return int(moment / BAR) % 4


def write_pads(mix):
    spans = [(0, 20), (20, 22), (22, 40), (40, 46.6), (46.6, 52.4), (52.4, 58)]
    for first, last in spans:
        for start in bars(first, last):
            level = 0.45 if first in (0, 40) else 0.7
            for index, note in enumerate(CHORDS[chord_index(start)]["pad"]):
                mix.add("pads", pad_note(note, BAR + 0.1), start,
                        pan=(index - 2) * 0.3, gain=level)


def write_arpeggio(mix, first, last, octave=0, gain=0.7, sparkle=False):
    for start in bars(first, last):
        tones = CHORDS[chord_index(start)]["tones"]
        for step, index in enumerate(ARPEGGIO):
            when = start + step * BEAT / 2
            pan = -0.4 + 0.11 * step
            mix.add("arps", pluck(tones[index] + octave, 1.8), when, pan=pan, gain=gain)
            if sparkle and step % 2 == 0:
                mix.add("bells", bell(tones[index] + octave + 12, 1.2), when,
                        pan=-pan, gain=0.12)


def write_melody(mix, first, last, octave=0, gain=0.5):
    for start in bars(first, last):
        for offset, note, length in MELODY[chord_index(start)]:
            moment = start + offset * BEAT
            mix.add("lead", pluck(note + octave, 2.6, 1.2), moment, pan=0.2, gain=gain)
            mix.add("lead", lead_note(note + octave, length * BEAT), moment,
                    pan=0.05, gain=0.8)


def write_drums(mix, first, last, kicks, level):
    hard = level == "hard"
    for start in bars(first, last):
        for beat in range(4):
            moment = start + beat * BEAT
            mix.add("kicks", kick(hard), moment, gain=1.0 if hard else 0.85)
            kicks.append(moment)
            if level == "soft":
                continue
            if beat in (1, 3):
                mix.add("drums", clap(), moment, pan=0.1, gain=1.1)
                if hard:
                    mix.add("drums", snare(), moment, gain=1.2)
            mix.add("drums", hat(hard and beat % 2 == 1), moment + BEAT / 2,
                    pan=0.3, gain=0.8)


def write_bass(mix, first, last, hard=False):
    for start in bars(first, last):
        root = CHORDS[chord_index(start)]["root"]
        for step in range(8):
            note = root + (12 if step % 4 == 3 else 0)
            moment = start + step * BEAT / 2
            if hard:
                mix.add("bass", bass_hard(note, BEAT * 0.45), moment, gain=0.95)
            else:
                mix.add("bass", bass_note(note, BEAT * 0.9), moment, gain=0.9)


def write_stabs(mix, first, last):
    for start in bars(first, last):
        for offset in (0.5, 1.5, 2.5, 3.5):
            mix.add("stabs", stab(chord_index(start)), start + offset * BEAT, gain=0.55)


def write_swell(mix, first, last):
    length = last - first
    time = seconds(length)
    signal = np.zeros_like(time)
    for note, weight in ((74, 1.0), (81, 0.7), (86, 0.5), (90, 0.3)):
        for cents in (-6, 6):
            signal += weight * np.sin(2 * np.pi * frequency(note) * 2 ** (cents / 1200) * time)
    rise = (time / length) ** 2.5
    shimmer = 1 + 0.12 * np.sin(2 * np.pi * 5.5 * time)
    mix.add("fx", signal * rise * shimmer * 0.05, first)


def write_stars(mix, first, last):
    for index, start in enumerate(np.arange(first, last, BEAT * 1.5)):
        note = STAR_NOTES[index % len(STAR_NOTES)] - 12 * (index % 3 == 2)
        mix.add("bells", bell(note), start, pan=rng.uniform(-0.6, 0.6), gain=0.6)


def write_effects(mix):
    for moment, length, low, high in (
        (23.2, 1.5, 250, 5000),
        (25.2, 1.6, 200, 4500),
        (26.6, 1.4, 600, 7000),
        (30.2, 1.6, 200, 6500),
        (52.8, 1.3, 400, 7000),
    ):
        mix.add("fx", sweep(length, low, min(high, 3200), 1.8), moment, gain=0.3)
    mix.add("fx", sweep(1.9, 3200, 300, 1.8, rising=False), 44.8, gain=0.3)
    for first, last in BUILDS:
        write_swell(mix, first, last)
    for moment in (22.0, 31.7, 46.8, 52.4):
        mix.add("hits", impact(), moment, gain=1.0)
    for moment in (5.0, 29.4):
        mix.add("hits", impact(1.6), moment, gain=0.35)


def write_lights_out(mix):
    mix.add("hits", impact(3.5), 40.0, gain=1.0)


def write_finale(mix):
    for index, note in enumerate((50, 57, 62, 66, 69, 74, 78, 81)):
        mix.add("arps", pluck(note, 3.2), 52.4 + index * 0.06, pan=-0.4 + 0.1 * index)
    for index, start in enumerate(np.arange(53.6, 56.8, BEAT)):
        note = (74, 78, 81, 86, 85, 81, 78, 74)[index % 8]
        mix.add("bells", bell(note, 3.0), start, pan=rng.uniform(-0.5, 0.5), gain=0.55)
    mix.add("arps", pluck(74, 4.0), 56.4, gain=0.7)


def arrange():
    mix = Mix()
    kicks = []
    write_pads(mix)
    write_arpeggio(mix, 2, 20, gain=0.5)
    write_arpeggio(mix, 20, 22, octave=12, gain=0.6)
    write_arpeggio(mix, 22, 40, gain=0.6, sparkle=True)
    write_arpeggio(mix, 46.6, 52.4, octave=12, gain=0.6, sparkle=True)
    write_melody(mix, 22, 38)
    write_melody(mix, 46.8, 52.4, octave=12, gain=0.6)
    write_drums(mix, 4, 12, kicks, "soft")
    write_drums(mix, 12, 21.5, kicks, "medium")
    write_drums(mix, 22, 40, kicks, "hard")
    write_drums(mix, 46.8, 52.4, kicks, "hard")
    write_bass(mix, 8, 21.5)
    write_bass(mix, 22, 40, hard=True)
    write_bass(mix, 46.8, 52.4, hard=True)
    write_stabs(mix, 22, 40)
    write_stabs(mix, 46.8, 52.4)
    write_stars(mix, 40.6, 46.4)
    write_effects(mix)
    write_lights_out(mix)
    write_finale(mix)
    return mix, kicks


def close_filter(master):
    first, last = CLOSING[0][0], CLOSING[-1][0]
    start, end = int(first * RATE), int(last * RATE)
    segment = master[:, start:end]
    versions = [band(segment, None, cutoff, 2) for cutoff in CUTOFFS]
    moments = np.linspace(first, last, end - start)
    knots, levels = zip(*CLOSING)
    position = np.interp(moments, knots, levels)
    lower = np.minimum(position.astype(int), len(CUTOFFS) - 2)
    amount = position - lower
    mixed = np.zeros_like(segment)
    for index, version in enumerate(versions):
        weight = np.where(lower == index, 1 - amount, 0) + np.where(lower + 1 == index, amount, 0)
        mixed += version * weight
    master[:, start:end] = mixed
    return master


def soft_limit(signal, knee=0.7):
    magnitude = np.abs(signal)
    over = np.maximum(magnitude - knee, 0)
    limited = np.where(magnitude > knee, knee + (1 - knee) * np.tanh(over / (1 - knee)),
                       magnitude)
    return np.sign(signal) * limited


def render():
    mix, kicks = arrange()
    tracks = mix.tracks
    duck = ducking(kicks)
    pads = band(tracks["pads"], 170, 7000) * duck
    bass = band(tracks["bass"], 30, 1800) * duck
    stabs = band(tracks["stabs"], 320, 8500) * duck
    arps = band(tracks["arps"], 140, 11000)
    lead = band(tracks["lead"], 220, 9000)
    bells = band(tracks["bells"], 400, 14000)
    kicks_bus = band(tracks["kicks"], 28, 9000)
    drums = band(tracks["drums"], 120, 17000)
    fx = band(tracks["fx"], 120, 9000)
    hits = band(tracks["hits"], 25, 12000)
    dry = kicks_bus + drums + bass + 0.8 * pads + stabs + arps + lead + bells + fx + hits
    send = 0.55 * pads + 0.8 * arps + 0.7 * lead + 1.0 * bells + 0.5 * fx
    send += 0.3 * drums + 0.35 * stabs + 0.25 * hits
    space = band(convolve(send, reverb_impulse()), 200, 8000, 2)
    delays = echo(arps + 0.9 * lead + bells)
    master = dry + 0.55 * space + 0.35 * delays
    moments = np.linspace(0, DURATION, master.shape[1])
    master = master * np.interp(moments, *zip(*INTENSITY))
    master = band(master, 24, None, 2)
    master = close_filter(master)
    master *= np.minimum(moments / 0.3, 1.0) * np.minimum((DURATION - moments) / 1.8, 1.0)
    master = master / np.percentile(np.abs(master), 99.5) * 0.62
    master = soft_limit(master)
    dither = (rng.random(master.shape) - rng.random(master.shape)) / 32768
    return np.clip((master + dither) * 0.84 * 32767, -32767, 32767).astype(np.int16)


def save(samples, path):
    with wave.open(str(path), "wb") as target:
        target.setnchannels(2)
        target.setsampwidth(2)
        target.setframerate(RATE)
        target.writeframes(samples.T.copy().tobytes())


if __name__ == "__main__":
    OUT.mkdir(exist_ok=True)
    save(render(), OUT / "music.wav")
