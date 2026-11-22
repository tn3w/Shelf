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
TABLE = 2048
FINALE = 52.3
DROPS = (22.0, 46.8)
DRIVE = 1.3
LEVEL = 1.6
CEILING = 0.89
BUSES = ("kick", "snare", "drums", "sub", "bass", "pad", "dark", "saw", "lead", "keys",
         "pulse", "arp", "bells", "fx", "hits")

DM, BB, FM, CM = range(4)
CHORDS = {
    DM: dict(root=38, pad=(50, 57, 62, 65, 69)),
    BB: dict(root=34, pad=(46, 53, 58, 62, 65)),
    FM: dict(root=41, pad=(53, 57, 60, 65, 69)),
    CM: dict(root=36, pad=(48, 55, 60, 64, 67)),
}
HOOK = {
    DM: ((0, 69, 1), (1, 74, 1.5), (2.5, 76, 0.5), (3, 77, 1)),
    BB: ((0, 74, 1.5), (1.5, 72, 0.5), (2, 70, 1), (3, 72, 1)),
    FM: ((0, 69, 1), (1, 72, 1.5), (2.5, 77, 0.5), (3, 81, 1)),
    CM: ((0, 79, 1.5), (1.5, 77, 0.5), (2, 76, 1), (3, 72, 1)),
}
TWINKLES = (89, 91, 93, 96, 98, 101)


def cycle(start, count):
    return [(start + index * BAR, index % 4) for index in range(count)]


CHANGES = [
    *cycle(0, 8), (16, DM), (18, BB), (20, CM),
    *cycle(22, 8), (38, BB), (40, DM), (42, BB), (44, CM),
    (46.8, DM), (48.8, BB), (50.8, CM), (FINALE, FM), (DURATION, FM),
]
INTENSITY = [
    (0, 0.3), (4, 0.36), (12, 0.55), (18, 0.6), (21.75, 0.72), (22, 1.0), (40, 1.0),
    (40.4, 0.62), (44.8, 0.62), (46.55, 0.85), (46.8, 1.0), (52.3, 1.0), (54, 0.8),
    (58, 0.65),
]
DARKNESS = [(39.95, 0.0), (40.3, 1.0), (44.8, 1.0), (46.5, 0.0)]
ROLLS = [(18.0, 21.75), (38.0, 39.9), (44.8, 46.55)]
RISERS = [(18.0, 21.75), (37.0, 40.0), (44.8, 46.55)]
IMPACTS = [(5.0, 0.3), (22.0, 1.0), (30.0, 0.55), (32.0, 1.0), (40.0, 1.0), (46.8, 1.0),
           (52.3, 1.0)]
CRASHES = [(12.0, 0.35), (22.0, 1.0), (30.0, 0.7), (32.0, 1.0), (40.0, 0.8), (46.8, 1.0),
           (50.8, 0.85), (52.3, 1.0)]

rng = np.random.default_rng(11)


def frequency(note):
    return 440.0 * 2 ** ((note - 69) / 12)


def seconds(length):
    return np.arange(int(length * RATE)) / RATE


def steps(first, last, step):
    return np.arange(first, last - 1e-6, step)


def detune(hertz, cents):
    return hertz * 2 ** (cents / 1200)


def sine(hertz, time):
    return np.sin(2 * np.pi * hertz * time)


def attack(time, length):
    return np.minimum(time / length, 1.0)


def envelope(time, rise, length, release):
    fall = np.where(time > length, np.exp(-(time - length) / release), 1.0)
    return attack(time, rise) * fall


def normalized(signal, peak):
    return signal / np.max(np.abs(signal)) * peak


def noise(size):
    return rng.standard_normal(size)


def band(signal, low=None, high=None, order=4):
    size = signal.shape[-1]
    frequencies = np.fft.rfftfreq(size, 1 / RATE)
    gain = np.ones_like(frequencies)
    if low:
        gain /= np.sqrt(1 + (low / np.maximum(frequencies, 1e-3)) ** (2 * order))
    if high:
        gain /= np.sqrt(1 + (frequencies / high) ** (2 * order))
    return np.fft.irfft(np.fft.rfft(signal, axis=-1) * gain, size, axis=-1)


def taper(signal):
    head = min(int(0.002 * RATE), len(signal))
    tail = min(int(np.clip(len(signal) * 0.1, 0.006 * RATE, 0.08 * RATE)), len(signal))
    shaped = signal.copy()
    shaped[:head] *= np.linspace(0, 1, head)
    shaped[len(shaped) - tail :] *= np.cos(np.linspace(0, np.pi / 2, tail)) ** 2
    return shaped


class Mix:
    def __init__(self):
        self.tracks = {name: np.zeros((2, int(DURATION * RATE))) for name in BUSES}

    def add(self, bus, signal, start, pan=0.0, gain=1.0):
        track = self.tracks[bus]
        first = int(round(start * RATE))
        last = min(first + len(signal), track.shape[1])
        if first >= track.shape[1]:
            return
        signal = taper(signal)[: last - first]
        angle = (pan + 1) * np.pi / 4
        track[0, first:last] += signal * np.cos(angle) * gain
        track[1, first:last] += signal * np.sin(angle) * gain


def segments(first, last):
    for (start, chord), (end, _) in zip(CHANGES, CHANGES[1:]):
        if end > first and start < last:
            yield max(start, first), min(end, last), start, chord


def hook_notes(first, last):
    notes = []
    for begin, finish, start, chord in segments(first, last):
        for offset, note, length in HOOK[chord]:
            moment = start + offset * BEAT
            if begin <= moment < finish:
                notes.append((moment, note, length))
    return notes


@lru_cache(maxsize=None)
def saw_table(note, top):
    phase = np.arange(TABLE) / TABLE
    count = max(1, int(top / frequency(note)))
    partials = range(1, count + 1)
    waves = (np.sin(2 * np.pi * partial * phase) / partial for partial in partials)
    return sum(waves) / 1.8


def oscillate(table, hertz, time, offset=0.0):
    phase = (hertz * time + offset) % 1 * TABLE
    return np.interp(phase, np.arange(TABLE + 1), np.append(table, table[0]))


def supersaw(note, time, voices, spread, top, seed=0.0):
    table = saw_table(note, top)
    detunes = np.linspace(-spread, spread, voices)
    layers = [oscillate(table, detune(frequency(note), cents), time, seed + 0.37 * index)
              for index, cents in enumerate(detunes)]
    return sum(layers) / np.sqrt(voices)


@lru_cache(maxsize=None)
def piano(note, length=3.0):
    time = seconds(length)
    signal = np.zeros_like(time)
    for partial in range(1, 13):
        hertz = frequency(note) * partial * np.sqrt(1 + 0.0003 * partial**2)
        decay = 2.6 / partial**0.7 / (1 + (note - 60) / 40)
        chorus = sine(detune(hertz, -1), time) + sine(detune(hertz, 1), time)
        signal += chorus * np.exp(-time / decay) / partial**1.5 * (hertz < 10000)
    hammer = band(noise(len(time)), 300, 3000, 2) * np.exp(-time / 0.008) * 0.15
    return (signal * 0.5 + hammer) * envelope(time, 0.002, length - 0.4, 0.12) * 0.3


@lru_cache(maxsize=None)
def pluck(note, length=0.7):
    time = seconds(length)
    hertz = frequency(note)
    saw = oscillate(saw_table(note, 6000), hertz, time) * np.exp(-time / 0.07)
    body = sine(hertz, time) * np.exp(-time / 0.25)
    return (saw * 0.6 + body * 0.5) * attack(time, 0.002) * 0.3


@lru_cache(maxsize=None)
def bell(note, length=3.0):
    time = seconds(length)
    hertz = frequency(note)
    depth = 2.0 * np.exp(-time / 0.4)
    tone = np.sin(2 * np.pi * hertz * time + depth * sine(hertz * 3.5, time))
    shimmer = 0.3 * sine(hertz * 2, time) * np.exp(-time / 0.5)
    return (tone + shimmer) * np.exp(-time / 1.3) * attack(time, 0.002) * 0.22


@lru_cache(maxsize=None)
def brass(note, length):
    time = seconds(length + 0.3)
    hertz = frequency(note)
    vibrato = 1 + 0.005 * sine(5.2, time) * attack(time, 0.6)
    phase = 2 * np.pi * hertz * np.cumsum(vibrato) / RATE
    bright = 1.5 + 8 * attack(time, 0.05) * (0.55 + 0.45 * np.exp(-time / 0.3))
    partials = range(1, int(8000 / hertz) + 1)
    signal = sum(np.sin(partial * phase) / partial * np.exp(-partial / bright)
                 for partial in partials)
    return np.tanh(signal * 1.8) * envelope(time, 0.02, length, 0.07) * 0.4


@lru_cache(maxsize=None)
def lead_note(note, length):
    time = seconds(length + 0.3)
    shine = supersaw(note + 12, time, 5, 14, 10000) * envelope(time, 0.01, length, 0.07)
    return brass(note, length) + band(shine, 900, 9000, 2) * 0.12


@lru_cache(maxsize=None)
def saw_chord(chord, length, side):
    time = seconds(length + 0.15)
    notes = CHORDS[chord]["pad"]
    voices = sum(supersaw(note + 12, time, 7, 26, 9000, side) for note in notes[1:])
    voices += 0.7 * supersaw(notes[0], time, 5, 16, 4000, side)
    return band(voices, 180, 10000) * envelope(time, 0.006, length, 0.05) * 0.07


@lru_cache(maxsize=None)
def pad_chord(chord, length):
    time = seconds(length + 1.6)
    notes = CHORDS[chord]["pad"]
    voices = sum(supersaw(note, time, 3, 9, 2400, 0.2 * index)
                 for index, note in enumerate(notes))
    return voices * envelope(time, 0.5, length, 0.45) * 0.05


@lru_cache(maxsize=None)
def sub(note, length):
    time = seconds(length + 0.05)
    return sine(frequency(note), time) * envelope(time, 0.01, length, 0.012) * 0.6


@lru_cache(maxsize=None)
def bass(note, length):
    time = seconds(length + 0.05)
    hertz = frequency(note)
    table = saw_table(note, 3000)
    body = sum(oscillate(table, detune(hertz, cents), time, 0.3 * index)
               for index, cents in enumerate((-7, 7)))
    grit = band(np.tanh(body * 2.0), 170, 1800)
    return grit * envelope(time, 0.004, length, 0.015) * 0.35


@lru_cache(maxsize=None)
def kick(hard=True):
    time = seconds(0.6)
    start = 150 if hard else 95
    phase = 2 * np.pi * (45 * time + (start - 45) * 0.04 * (1 - np.exp(-time / 0.04)))
    body = np.sin(phase) * np.exp(-time / (0.16 if hard else 0.14))
    knock = band(noise(len(time)), 120, 700, 2) * np.exp(-time / 0.015)
    click = band(noise(len(time)), 2500, 11000, 2) * np.exp(-time / 0.003)
    drive = 2.0 if hard else 1.3
    return np.tanh(body * drive + knock * 1.2 + click * 0.5) * 0.95


@lru_cache(maxsize=None)
def snare():
    time = seconds(0.45)
    tone = np.sin(2 * np.pi * 190 * time * (1 + 0.3 * np.exp(-time / 0.015)))
    rattle = band(noise(len(time)), 1200, 10000, 2) * np.exp(-time / 0.13)
    return normalized(tone * np.exp(-time / 0.08) + normalized(rattle, 0.8), 0.8)


@lru_cache(maxsize=None)
def clap():
    signal = np.zeros(int(0.4 * RATE))
    for offset, decay in ((0, 0.01), (0.012, 0.01), (0.024, 0.09)):
        burst = band(noise(int(0.35 * RATE)), 900, 7000, 2)
        burst *= np.exp(-seconds(0.35) / decay)
        start = int(offset * RATE)
        signal[start : start + len(burst)] += burst
    return normalized(signal, 0.6)


@lru_cache(maxsize=None)
def hat(open_hat=False):
    time = seconds(0.45 if open_hat else 0.08)
    metal = sum(sine(hertz, time) for hertz in (3150, 4720, 6370, 8430))
    sizzle = band(noise(len(time)) + metal * 0.2, 7000, 16000, 3)
    return normalized(sizzle * np.exp(-time / (0.12 if open_hat else 0.018)), 0.3)


@lru_cache(maxsize=None)
def crash():
    time = seconds(4.0)
    metal = sum(np.sin(2 * np.pi * hertz * time + rng.uniform(0, 6))
                for hertz in rng.uniform(3000, 11000, 40))
    wash = band(noise(len(time)) + 0.05 * metal, 3000, 16000, 2)
    return normalized(wash * np.exp(-time / 1.1) * attack(time, 0.001), 0.5)


@lru_cache(maxsize=None)
def impact(length=3.5):
    time = seconds(length)
    phase = 2 * np.pi * (38 * time + 72 * 0.12 * (1 - np.exp(-time / 0.12)))
    boom = np.sin(phase) * np.exp(-time / 0.25)
    thud = band(noise(len(time)), 90, 400, 2) * np.exp(-time / 0.06)
    air = band(noise(len(time)), 2000, 12000, 2) * np.exp(-time / 0.5)
    return np.tanh(boom * 2 + thud * 1.5 + air * 0.1) * 0.9


@lru_cache(maxsize=None)
def riser(length):
    time = seconds(length)
    progress = time / length
    hiss = noise(len(time))
    hiss = band(hiss, 300, 2500) * (1 - progress) + band(hiss, 2500, 13000) * progress
    tone = np.tanh(2 * np.sin(2 * np.pi * np.cumsum(180 * 2 ** (3 * progress)) / RATE))
    return (hiss * 0.6 + tone * 0.1) * progress**2.2 * attack(time, 0.05)


def write_pads(mix, bus, first, last, gain):
    for begin, finish, _, chord in segments(first, last):
        mix.add(bus, pad_chord(chord, round(finish - begin, 3)), begin, gain=gain)


def write_saws(mix, first, last):
    for begin, finish, _, chord in segments(first, last):
        for side in (-1, 1):
            mix.add("saw", saw_chord(chord, round(finish - begin, 3), side), begin,
                    pan=0.75 * side)


def write_hook(mix, bus, voice, first, last, octave=0, gain=1.0):
    for moment, note, _ in hook_notes(first, last):
        mix.add(bus, voice(note + octave), moment, pan=0.15, gain=gain)


def write_lead(mix, first, last, octaves):
    for moment, note, length in hook_notes(first, last):
        for octave, gain in octaves:
            mix.add("lead", lead_note(note + octave, length * BEAT), moment, gain=gain)


def write_arp(mix, bus, first, last, step, pattern, octave, gain):
    for begin, finish, _, chord in segments(first, last):
        notes = CHORDS[chord]["pad"]
        for index, moment in enumerate(steps(begin, finish, step)):
            note = notes[pattern[index % len(pattern)]] + octave
            mix.add(bus, pluck(note), moment, pan=0.35 * np.sin(index * 1.7), gain=gain)


def write_bass(mix, first, last, mode, gain=1.0):
    for begin, finish, _, chord in segments(first, last):
        root = CHORDS[chord]["root"]
        length = round(finish - begin, 3)
        mix.add("sub", sub(root, length), begin, gain=gain)
        if mode == "long":
            mix.add("bass", bass(root, length), begin, gain=0.3 * gain)
            continue
        for index, moment in enumerate(steps(begin, finish, BEAT / 2)):
            if mode == "offbeat" and index % 2 == 0:
                continue
            note = root + 12 * (mode == "drive" and index % 4 == 3)
            mix.add("bass", bass(note, 0.2), moment, gain=gain)


def write_backbeat(mix, moment, index, hard):
    if index % 2:
        mix.add("snare", clap(), moment, pan=0.05, gain=0.9)
    if index % 2 and hard:
        mix.add("snare", snare(), moment)
    mix.add("drums", hat(hard), moment + BEAT / 2, pan=0.25, gain=0.8)
    if hard:
        for offset in (0.25, 0.75):
            mix.add("drums", hat(), moment + offset * BEAT, pan=-0.3, gain=0.45)


def write_beat(mix, first, last, kicks, level, gain=1.0):
    for index, moment in enumerate(steps(first, last, BEAT)):
        kicks.append(moment)
        mix.add("kick", kick(level != "soft"), moment, gain=gain)
        if level in ("medium", "hard"):
            write_backbeat(mix, moment, index, level == "hard")


def write_roll(mix, first, last):
    moment = first
    while moment < last - 1e-6:
        progress = (moment - first) / (last - first)
        divisions = 2 ** sum(progress >= edge for edge in (0.35, 0.65, 0.88))
        mix.add("snare", snare(), moment, pan=0.15 * np.sin(moment * 9),
                gain=0.2 + 0.8 * progress**1.5)
        moment += BEAT / divisions


def write_twinkles(mix, first, last):
    for moment in steps(first, last, BEAT / 2):
        if rng.random() < 0.45:
            continue
        mix.add("bells", bell(int(rng.choice(TWINKLES)), 2.0), moment,
                pan=rng.uniform(-0.7, 0.7), gain=0.35)


def write_effects(mix):
    for first, last in ROLLS:
        write_roll(mix, first, last)
    for first, last in RISERS:
        mix.add("fx", riser(round(last - first, 3)), first, gain=0.5)
    for moment in (22.0, 40.0, 46.8):
        mix.add("fx", riser(2.5)[::-1], moment, gain=0.35)
    for moment, gain in IMPACTS:
        mix.add("hits", impact(), moment, gain=gain)
    for moment, gain in CRASHES:
        mix.add("drums", crash(), moment, pan=-0.25, gain=gain)
        mix.add("drums", crash()[400:], moment, pan=0.3, gain=0.8 * gain)


def write_intro(mix):
    write_pads(mix, "pad", 0, 22, 0.9)
    write_hook(mix, "keys", piano, 0, 12, gain=0.9)
    for begin, _, _, chord in segments(0, 12):
        root = CHORDS[chord]["root"]
        mix.add("keys", piano(root + 12), begin, pan=-0.2, gain=0.5)
        mix.add("keys", piano(root + 19), begin + BEAT, pan=-0.1, gain=0.35)
    mix.add("bells", bell(77), 5.0, gain=0.4)
    write_arp(mix, "pulse", 4, 22, BEAT / 2, (0, 2, 1, 2), 12, 0.45)
    write_bass(mix, 8, 12, "long", 0.5)


def write_rise(mix, kicks):
    write_beat(mix, 8, 12, kicks, "soft", 0.55)
    write_beat(mix, 12, 20, kicks, "medium", 0.85)
    write_beat(mix, 20, 21.75, kicks, "kick", 0.85)
    write_bass(mix, 12, 21.75, "offbeat", 0.8)
    write_hook(mix, "keys", pluck, 12, 21.75, 12, 0.7)
    write_hook(mix, "bells", bell, 12, 21.75, 12, 0.25)


def write_drop(mix, kicks, first, last, octaves):
    write_beat(mix, first, last, kicks, "hard")
    write_bass(mix, first, last, "drive")
    write_saws(mix, first, last)
    write_pads(mix, "pad", first, last, 0.6)
    write_lead(mix, first, last, octaves)
    write_arp(mix, "arp", first, last, BEAT / 4, (1, 2, 3, 4, 2, 3, 4, 3), 24, 0.35)


def write_breakdown(mix):
    write_pads(mix, "dark", 40, 46.8, 1.2)
    write_bass(mix, 40, 46.55, "long", 0.45)
    write_hook(mix, "bells", bell, 40, 44.8, 12, 0.55)
    write_twinkles(mix, 40.5, 46.4)
    write_arp(mix, "pulse", 44.8, 46.55, BEAT / 4, (0, 2, 1, 3), 12, 0.4)


def write_finale(mix):
    for side in (-1, 1):
        chord = saw_chord(FM, 5.5, side) * np.exp(-seconds(5.65) / 1.8)
        mix.add("saw", chord, FINALE, pan=0.75 * side)
    write_pads(mix, "pad", FINALE, DURATION, 1.0)
    write_bass(mix, FINALE, DURATION, "long", 0.8)
    for octave, gain in ((0, 0.9), (12, 0.6)):
        mix.add("lead", lead_note(77 + octave, 2.0), FINALE, gain=gain)
    write_hook(mix, "keys", piano, 54.0, 56.0, gain=0.8)
    for index, note in enumerate((53, 60, 65, 69, 72, 77)):
        mix.add("keys", piano(note, 4.0), 56.0 + index * 0.04, pan=-0.3 + 0.12 * index,
                gain=0.6)
    for index, note in enumerate((89, 93, 96, 101)):
        mix.add("bells", bell(note), 53.0 + index * 0.75, pan=0.5 - 0.3 * index, gain=0.3)


def arrange():
    mix = Mix()
    kicks = []
    write_intro(mix)
    write_rise(mix, kicks)
    write_drop(mix, kicks, 22, 38, ((0, 1.0), (-12, 0.45)))
    write_drop(mix, kicks, 38, 40, ((0, 1.0), (-12, 0.45)))
    write_breakdown(mix)
    write_drop(mix, kicks, 46.8, FINALE, ((12, 0.75), (0, 0.9)))
    write_finale(mix)
    write_effects(mix)
    return mix, kicks


def convolve(signal, impulse):
    size = 1 << int(np.ceil(np.log2(signal.shape[1] + impulse.shape[1])))
    spectrum = np.fft.rfft(signal, size) * np.fft.rfft(impulse, size)
    return np.fft.irfft(spectrum, size)[:, : signal.shape[1]]


def reverb_impulse(length, decay, predelay):
    time = seconds(length)
    impulse = noise((2, len(time))) * np.exp(-time / decay) * attack(time, 0.01)
    impulse = band(impulse, 200, 7000, 2)
    return np.pad(impulse, ((0, 0), (int(predelay * RATE), 0))) * 0.04


def echo(signal, delay=0.375, repeats=4, decay=0.45):
    output = np.zeros_like(signal)
    for repeat in range(1, repeats + 1):
        shift = int(delay * RATE * repeat)
        copy = signal[::-1] if repeat % 2 else signal
        output[:, shift:] += copy[:, :-shift] * decay**repeat
    return band(output, 300, 5000, 2)


def ducking(kicks, depth, release):
    gain = np.ones(int(DURATION * RATE))
    size = int(release * RATE * 3)
    curve = 1 - depth * np.exp(-seconds(release * 3) / (release / 2.5))
    for moment in kicks:
        start = int(round(moment * RATE))
        end = min(start + size, len(gain))
        gain[start:end] = np.minimum(gain[start:end], curve[: end - start])
    return gain


def curve(points):
    moments = np.arange(int(DURATION * RATE)) / RATE
    return np.interp(moments, *zip(*points))


def gate():
    points = [(0, 1)]
    for drop in DROPS:
        points += [(drop - 0.26, 1), (drop - 0.24, 0), (drop - 0.002, 0), (drop, 1)]
    return curve(points + [(DURATION, 1)])


def darken(signal):
    amount = curve([(0, 0), *DARKNESS, (DURATION, 0)])
    return signal * (1 - amount) + band(signal, None, 650, 2) * amount


def limit(signal, ceiling, block=256, radius=12):
    peak = np.abs(signal).max(axis=0)
    count = -(-len(peak) // block)
    padded = np.pad(peak, (0, count * block - len(peak)))
    blocks = padded.reshape(count, block).max(axis=1)
    gain = np.minimum(1.0, ceiling / np.maximum(blocks, 1e-9))
    shifts = range(-radius - 1, radius + 2)
    gain = np.minimum.reduce([np.roll(gain, shift) for shift in shifts])
    gain = np.convolve(gain, np.ones(2 * radius + 1) / (2 * radius + 1), "same")
    centers = (np.arange(count) + 0.5) * block
    return signal * np.interp(np.arange(len(peak)), centers, gain)


def render():
    mix, kicks = arrange()
    tracks = mix.tracks
    pump = ducking(kicks, 0.8, 0.2)
    lift = ducking(kicks, 0.3, 0.12)
    snare_bus = band(tracks["snare"], 150, 15000)
    room = convolve(snare_bus, reverb_impulse(0.9, 0.2, 0.006))
    snare_bus += 0.5 * band(room, 250, 9000)
    kick_bus = band(tracks["kick"], 25, 12000)
    drums = band(tracks["drums"], 300, 18000)
    sub_bus = band(tracks["sub"], 30, 200) * ducking(kicks, 0.97, 0.16)
    bass_bus = band(tracks["bass"], 150, 2500) * pump
    pad = band(tracks["pad"], 160, 6000) * pump
    saw = band(tracks["saw"], 220, 11000) * pump
    lead = band(tracks["lead"], 180, 10000) * lift
    keys = band(tracks["keys"], 120, 12000)
    pulse = band(tracks["pulse"], 150, 4000) * lift
    arp = band(tracks["arp"], 1200, 14000) * pump
    bells = band(tracks["bells"], 400, 15000)
    dark = band(tracks["dark"], 150, 700, 2)
    fx = band(tracks["fx"], 150, 14000)
    hits = band(tracks["hits"], 25, 14000)
    send = pad * 0.5 + saw * 0.35 + lead * 0.6 + keys * 0.8 + pulse * 0.5 + arp * 0.6
    send += bells * 1.0 + snare_bus * 0.35 + fx * 0.4 + dark * 0.8 + hits * 0.15
    space = band(convolve(send, reverb_impulse(3.2, 0.9, 0.03)), 200, 8000, 2)
    delays = echo(lead * 0.6 + keys + pulse * 0.5 + bells)
    core = kick_bus + 2 * snare_bus + 0.8 * drums + 0.7 * sub_bus + bass_bus
    core += 0.8 * pad + 1.8 * saw + 0.8 * lead + keys + pulse + arp
    core += 0.5 * space + 0.3 * delays
    music = (darken(core) + bells + dark + fx) * curve(INTENSITY)
    master = band(music + 0.9 * hits, 30, None, 4)
    master += 0.35 * band(master, 5000, None, 2)
    master = master * gate() * LEVEL / np.percentile(np.abs(master), 99.9)
    low = band(master, None, 120, 4)
    master = low + np.tanh((master - low) * DRIVE) / DRIVE
    moments = np.arange(master.shape[1]) / RATE
    master *= attack(moments, 0.05) * np.clip((DURATION - moments) / 2.5, 0, 1) ** 1.5
    master = limit(master, CEILING)
    dither = (rng.random(master.shape) - rng.random(master.shape)) / 32768
    return np.clip((master + dither) * 32767, -32767, 32767).astype(np.int16)


def save(samples, path):
    with wave.open(str(path), "wb") as target:
        target.setnchannels(2)
        target.setsampwidth(2)
        target.setframerate(RATE)
        target.writeframes(samples.T.copy().tobytes())


if __name__ == "__main__":
    OUT.mkdir(exist_ok=True)
    save(render(), OUT / "music.wav")
