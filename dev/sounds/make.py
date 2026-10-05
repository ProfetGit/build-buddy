#!/usr/bin/env python3
"""Synthesizes Cyanotype's own interface sounds (soft ticks, clicks, chimes) and writes them into the mod.

numpy makes the samples, ffmpeg's Vorbis encoder turns them into .ogg. Output: src/main/resources/assets/cyanotype/sounds/*.ogg
and sounds.json. Run: python3 dev/sounds/make.py   (add --wav to keep the WAVs in dev/sounds/out for a listen)

Design rules (PRD 6b): quiet and short, vanilla-like loudness, never louder than vanilla UI. A sound is a pitched tone with a
fast exponential decay plus a touch of filtered noise for the "paper" feel; chimes are two or three partials.
"""
import json
import subprocess
import sys
import wave
from pathlib import Path

import numpy as np

SR = 44100
HERE = Path(__file__).resolve().parent
OUT = HERE.parent.parent / "src/main/resources/assets/cyanotype"
rng = np.random.default_rng(7)


def t(seconds):
    return np.arange(int(SR * seconds)) / SR


def env(n, attack=0.002, decay=30.0):
    x = np.arange(n) / SR
    a = np.minimum(1, x / attack)
    return a * np.exp(-decay * x)


def tone(freq, seconds, decay=30.0, attack=0.002, harmonics=((1, 1.0),)):
    x = t(seconds)
    y = np.zeros_like(x)
    for mult, amp in harmonics:
        y += amp * np.sin(2 * np.pi * freq * mult * x)
    return y * env(len(x), attack, decay)


def noise(seconds, lo=800, hi=6000, decay=60.0):
    n = int(SR * seconds)
    x = rng.standard_normal(n)
    # crude band-pass in the frequency domain
    f = np.fft.rfft(x)
    freqs = np.fft.rfftfreq(n, 1 / SR)
    f[(freqs < lo) | (freqs > hi)] = 0
    y = np.fft.irfft(f, n)
    y /= max(1e-9, np.abs(y).max())
    return y * env(n, 0.001, decay)


def sweep(f0, f1, seconds, decay=12.0):
    x = t(seconds)
    k = (f1 - f0) / seconds
    phase = 2 * np.pi * (f0 * x + 0.5 * k * x * x)
    return np.sin(phase) * env(len(x), 0.01, decay)


def mix(*parts):
    n = max(len(p) for p in parts)
    out = np.zeros(n)
    for p in parts:
        out[: len(p)] += p
    return out


def pad(y, lead=0.0):
    return np.concatenate([np.zeros(int(SR * lead)), y])


SOUNDS = {
    # a hair of high tick: hovering something
    "ui_hover": lambda: mix(0.35 * tone(2400, 0.05, decay=90), 0.12 * noise(0.03, 3000, 9000, 120)),
    # a press goes down: a low click with body
    "ui_press": lambda: mix(0.55 * tone(620, 0.09, decay=45, harmonics=((1, 1.0), (2, 0.3))), 0.25 * noise(0.04, 600, 4000, 90)),
    # and comes back up: lighter, higher
    "ui_release": lambda: mix(0.45 * tone(980, 0.08, decay=50, harmonics=((1, 1.0), (3, 0.15))), 0.18 * noise(0.03, 1500, 7000, 100)),
    # a panel or the wheel opening / closing: a short rising or falling paper sweep
    "ui_open": lambda: mix(0.30 * sweep(500, 1500, 0.16, decay=14), 0.14 * noise(0.14, 1200, 7000, 25)),
    "ui_close": lambda: mix(0.26 * sweep(1300, 450, 0.13, decay=18), 0.10 * noise(0.11, 900, 6000, 30)),
    # moving the highlight to another wheel segment
    "wheel_tick": lambda: mix(0.4 * tone(1700, 0.04, decay=110), 0.1 * noise(0.02, 2500, 8000, 150)),
    # the placement locks: a soft low thump and a two-note pluck
    "lock": lambda: mix(
        0.5 * tone(140, 0.18, decay=22),
        0.28 * noise(0.05, 300, 2500, 70),
        pad(0.34 * tone(1319, 0.45, decay=9, harmonics=((1, 1.0), (2, 0.2))), 0.03),
        pad(0.30 * tone(1976, 0.5, decay=8, harmonics=((1, 1.0), (2, 0.15))), 0.10),
    ),
    # a step of a drag snapping to the next block
    "snap": lambda: mix(0.42 * tone(1500, 0.045, decay=80), 0.15 * noise(0.025, 2000, 8000, 130)),
    # a layer, or the whole build, finished: three rising notes
    "complete": lambda: mix(
        0.30 * tone(988, 0.5, decay=7, harmonics=((1, 1.0), (2, 0.2))),
        pad(0.30 * tone(1319, 0.5, decay=7, harmonics=((1, 1.0), (2, 0.2))), 0.09),
        pad(0.32 * tone(1976, 0.7, decay=6, harmonics=((1, 1.0), (2, 0.2))), 0.18),
    ),
    # something cannot be done: two low short blips
    "error": lambda: mix(0.45 * tone(300, 0.09, decay=40), pad(0.45 * tone(240, 0.12, decay=35), 0.09)),
}


def write_wav(path, y):
    y = np.clip(y, -1, 1)
    # a short fade at both ends so nothing clicks
    n = len(y)
    fade = min(int(SR * 0.004), n // 2)
    y[:fade] *= np.linspace(0, 1, fade)
    y[-fade:] *= np.linspace(1, 0, fade)
    # normalise to a fixed, quiet peak: the game's UI volume does the rest
    peak = np.abs(y).max()
    if peak > 0:
        y = y / peak * 0.8
    pcm = (y * 32767).astype("<i2")
    with wave.open(str(path), "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(SR)
        w.writeframes(pcm.tobytes())


def main():
    keep = "--wav" in sys.argv
    sounds_dir = OUT / "sounds"
    sounds_dir.mkdir(parents=True, exist_ok=True)
    wav_dir = HERE / "out"
    wav_dir.mkdir(exist_ok=True)
    events = {}
    for name, make in SOUNDS.items():
        wav = wav_dir / f"{name}.wav"
        write_wav(wav, make())
        ogg = sounds_dir / f"{name}.ogg"
        subprocess.run(["ffmpeg", "-y", "-loglevel", "error", "-i", str(wav), "-ac", "2", "-c:a", "vorbis", "-strict", "-2", "-q:a", "5", str(ogg)], check=True)
        events[name] = {"sounds": [{"name": f"cyanotype:{name}", "volume": 1.0}]}
        if not keep:
            wav.unlink()
    (OUT / "sounds.json").write_text(json.dumps(events, indent=2) + "\n")
    if not keep:
        wav_dir.rmdir()
    print(f"wrote {len(SOUNDS)} sounds to {sounds_dir.relative_to(HERE.parent.parent)}")


if __name__ == "__main__":
    main()
