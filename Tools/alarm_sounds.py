#!/usr/bin/env python3
"""Synthesise Humogram's five threat-alarm sounds into TMessagesProj/src/main/res/raw.

Every sample is generated here from oscillators — no recording of any phone
maker's alarm is used or imitated note for note (Apple's and others' alarm
sounds are copyrighted). The brief was "as serious as a phone's default alarm,
but our own": five patterns with their own frequencies and rhythm.

Each sound is 44.1 kHz mono, peak-normalised to -1 dBFS (measured on the
decoded OGG, since Vorbis overshoots its input), 3-4 s long and built
to loop seamlessly: every pulse has its own 5-10 ms fade-in/out, continuous
tones run a whole number of cycles over the clip so the loop point lands on the
same phase, and the clip edges get a 6 ms fade so no loop can click.

    python Tools/alarm_sounds.py            # writes the five .ogg files
    python Tools/alarm_sounds.py --wav DIR  # also keeps the 16-bit WAVs in DIR

Needs numpy and an ffmpeg with libvorbis on PATH.
"""

import argparse
import os
import subprocess
import sys
import tempfile
import wave

import numpy as np

SR = 44100
PEAK_DBFS = -1.0
EDGE_FADE_S = 0.006
HERE = os.path.dirname(os.path.abspath(__file__))
RAW = os.path.normpath(os.path.join(HERE, "..", "TMessagesProj", "src", "main", "res", "raw"))


# ---------------------------------------------------------------------------
# building blocks
# ---------------------------------------------------------------------------

def t_axis(seconds):
    return np.arange(int(round(seconds * SR))) / SR


def phase_of(freq):
    """Running phase (in cycles) of an oscillator whose frequency varies per sample."""
    return np.cumsum(np.asarray(freq, dtype=np.float64)) / SR


def band_limited(phase_cycles, top_freq, kind):
    """Square or sawtooth from a phase track, with no harmonic above ~18 kHz (no aliasing)."""
    out = np.zeros_like(phase_cycles)
    n = 1
    while n * top_freq < 18000:
        if kind == "square":
            if n % 2 == 1:
                out += np.sin(2 * np.pi * n * phase_cycles) / n
        elif kind == "saw":
            out += ((-1) ** (n + 1)) * np.sin(2 * np.pi * n * phase_cycles) / n
        n += 1
    return out


def saturate(x, drive):
    """Soft clipping: grit without the hard edges that would alias."""
    return np.tanh(drive * x) / np.tanh(drive)


def envelope(n, attack_s, release_s):
    env = np.ones(n)
    a = max(1, int(attack_s * SR))
    r = max(1, int(release_s * SR))
    env[:a] = np.linspace(0, 1, a)
    env[-r:] = np.minimum(env[-r:], np.linspace(1, 0, r))
    return env


def place(track, signal, start_s):
    i = int(round(start_s * SR))
    track[i:i + len(signal)] += signal[: max(0, len(track) - i)]


def finish(x):
    """Edge fades, then peak-normalise to PEAK_DBFS."""
    x = x * envelope(len(x), EDGE_FADE_S, EDGE_FADE_S)
    peak = np.max(np.abs(x))
    return x * (10 ** (PEAK_DBFS / 20) / peak)


# ---------------------------------------------------------------------------
# the five sounds
# ---------------------------------------------------------------------------

def radar():
    """Bursts of four hard 1600 Hz beeps: 70 ms on, 50 ms off, 400 ms between bursts."""
    beep_s, gap_s, rest_s, bursts = 0.070, 0.050, 0.400, 4
    cycle = 4 * beep_s + 3 * gap_s + rest_s           # 0.83 s
    track = np.zeros(int(round(cycle * bursts * SR)))
    t = t_axis(beep_s)
    tone = band_limited(1600 * t, 1600, "square")
    # A faint second partial a little sharp makes it bite like a piezo.
    tone += 0.25 * np.sin(2 * np.pi * 3260 * t)
    beep = saturate(tone, 1.6) * envelope(len(t), 0.005, 0.008)
    for b in range(bursts):
        for k in range(4):
            place(track, beep, b * cycle + k * (beep_s + gap_s))
    return track


def klaxon():
    """Two-tone sawtooth, 650 <-> 950 Hz, 250 ms each, hard."""
    seg_s, pairs = 0.250, 7                            # 3.5 s
    n_seg = int(round(seg_s * SR))
    freq = np.concatenate([np.full(n_seg, 650.0), np.full(n_seg, 950.0)] * pairs)
    # 650 x 0.25 + 950 x 0.25 = 400 whole cycles per pair: the loop lands on phase 0.
    ph = phase_of(freq)
    saw = band_limited(ph, 950, "saw")
    # A slightly detuned twin thickens it the way a real horn's two drivers do.
    twin = band_limited(phase_of(freq * 1.004), 960, "saw")
    return saturate(0.6 * saw + 0.4 * twin, 2.2)


def siren():
    """Air-raid siren: 600 -> 1600 -> 600 Hz every 1.2 s, two rotors a minor third apart."""
    period, cycles = 1.2, 3                            # 3.6 s
    t = t_axis(period * cycles)
    sweep = 600 + 1000 * (1 - np.cos(2 * np.pi * t / period)) / 2
    # 1320 whole cycles per sweep for the low rotor, 1584 for the high one (x 6/5).
    low = phase_of(sweep)
    high = phase_of(sweep * 1.2)
    voice = (np.sin(2 * np.pi * low) + 0.35 * np.sin(4 * np.pi * low)
             + 0.6 * (np.sin(2 * np.pi * high) + 0.3 * np.sin(4 * np.pi * high)))
    return saturate(voice, 1.4)


def redlevel():
    """Low 180 Hz drone with a tritone above, throbbing at 4 Hz, short high beeps on top."""
    seconds = 3.0
    t = t_axis(seconds)
    # 180 Hz and 255 Hz (a tritone above, within 6 cents) both run whole cycles in 3 s.
    drone = (band_limited(180 * t, 180, "saw") + 0.7 * band_limited(255 * t, 255, "saw"))
    # A gentle low-pass keeps the drone a growl rather than a buzz.
    kernel = np.ones(9) / 9
    drone = np.convolve(drone, kernel, mode="same")
    throb = 0.35 + 0.65 * (0.5 + 0.5 * np.cos(2 * np.pi * 4 * t)) ** 2
    track = 0.75 * saturate(drone, 1.8) * throb
    # Two quick 2.5 kHz beeps on every other throb: an instrument panel in alarm.
    bt = t_axis(0.055)
    beep = saturate(band_limited(2500 * bt, 2500, "square"), 1.5) * envelope(len(bt), 0.005, 0.008)
    for k in range(6):
        place(track, 0.55 * beep, k * 0.5)
        place(track, 0.55 * beep, k * 0.5 + 0.11)
    return track


def evacuation():
    """Temporal-three evacuation pattern: three 0.5 s beeps 0.5 s apart, then 1.5 s, at ~3 kHz."""
    track = np.zeros(int(round(4.0 * SR)))
    t = t_axis(0.5)
    # A piezo sounder's tone: square-ish 3.1 kHz with a slight 8 Hz warble.
    ph = phase_of(3100 + 25 * np.sin(2 * np.pi * 8 * t))
    tone = saturate(band_limited(ph, 3125, "square"), 1.3)
    beep = tone * envelope(len(t), 0.008, 0.010)
    for k in range(3):
        place(track, beep, k * 1.0)
    return track


SOUNDS = [
    ("alarm_radar", radar),
    ("alarm_klaxon", klaxon),
    ("alarm_siren", siren),
    ("alarm_redlevel", redlevel),
    ("alarm_evacuation", evacuation),
]


# ---------------------------------------------------------------------------
# output
# ---------------------------------------------------------------------------

def write_wav(path, x):
    pcm = np.clip(np.round(x * 32767), -32768, 32767).astype("<i2")
    with wave.open(path, "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(SR)
        w.writeframes(pcm.tobytes())


def encode_ogg(wav_path, ogg_path):
    subprocess.run(
        ["ffmpeg", "-y", "-loglevel", "error", "-i", wav_path,
         "-c:a", "libvorbis", "-q:a", "6", "-ar", str(SR), "-ac", "1",
         "-map_metadata", "-1", ogg_path],
        check=True,
    )


def decoded_peak_dbfs(ogg_path):
    """Peak of the file as a phone will decode it — Vorbis overshoots its input slightly."""
    raw = subprocess.run(
        ["ffmpeg", "-loglevel", "error", "-i", ogg_path, "-f", "f32le", "-ac", "1", "-"],
        check=True, capture_output=True,
    ).stdout
    x = np.frombuffer(raw, dtype="<f4")
    return 20 * np.log10(np.max(np.abs(x)))


def write_ogg(x, wav_path, ogg_path):
    """Encode, then trim the level until the decoded peak is at PEAK_DBFS, not above it."""
    for _ in range(4):
        write_wav(wav_path, x)
        encode_ogg(wav_path, ogg_path)
        over = decoded_peak_dbfs(ogg_path) - PEAK_DBFS
        if over <= 0.05:
            break
        x = x * 10 ** (-over / 20)
    return x


def main():
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--wav", help="also keep the WAVs in this directory")
    parser.add_argument("--out", default=RAW, help="output directory (default: res/raw)")
    args = parser.parse_args()

    os.makedirs(args.out, exist_ok=True)
    with tempfile.TemporaryDirectory() as tmp:
        for name, make in SOUNDS:
            x = finish(make())
            wav_dir = args.wav or tmp
            os.makedirs(wav_dir, exist_ok=True)
            wav_path = os.path.join(wav_dir, name + ".wav")
            ogg_path = os.path.join(args.out, name + ".ogg")
            write_ogg(x, wav_path, ogg_path)
            print("%-18s %.2f s  decoded peak %.2f dBFS  -> %s (%d bytes)" % (
                name, len(x) / SR, decoded_peak_dbfs(ogg_path), ogg_path, os.path.getsize(ogg_path)))
    return 0


if __name__ == "__main__":
    sys.exit(main())
