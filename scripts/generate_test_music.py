"""Generate a reproducible 10-second bass-and-treble test clip."""

from pathlib import Path
import wave

import numpy as np


RATE = 44_100
DURATION = 10
OUTPUT = Path(__file__).resolve().parents[1] / "inputs" / "bass_treble_10s.wav"

# One bass note per second. Each note lasts 0.82 seconds, leaving a short gap.
BASS = [
    (40, "E2"), (43, "G2"), (45, "A2"), (47, "B2"), (48, "C3"),
    (47, "B2"), (45, "A2"), (43, "G2"), (40, "E2"), (40, "E2"),
]
# A brighter melody plays two notes per second above the bass line.
TREBLE = [
    64, 67, 71, 67, 69, 72, 71, 69, 72, 76,
    71, 69, 69, 67, 67, 64, 64, 67, 64, 71,
]


def frequency(pitch):
    return 440.0 * 2 ** ((pitch - 69) / 12)


def pluck(pitch, seconds, decay, harmonics):
    time = np.arange(round(seconds * RATE)) / RATE
    phase = 2 * np.pi * frequency(pitch) * time
    tone = sum(amplitude * np.sin(number * phase)
               for number, amplitude in enumerate(harmonics, start=1))
    attack = np.minimum(1, time / .012)
    release = np.minimum(1, (seconds - time) / .06)
    return tone * attack * release * np.exp(-decay * time)


def main():
    left = np.zeros(RATE * DURATION, dtype=np.float64)
    right = np.zeros_like(left)
    for second, (pitch, _) in enumerate(BASS):
        sound = .55 * pluck(pitch, .82, .48, (1, .33, .18, .08))
        start = second * RATE
        left[start:start + len(sound)] += .82 * sound
        right[start:start + len(sound)] += sound
    for half_second, pitch in enumerate(TREBLE):
        sound = .25 * pluck(pitch, .38, 1.7, (1, .55, .24, .1))
        start = half_second * RATE // 2
        left[start:start + len(sound)] += sound
        right[start:start + len(sound)] += .68 * sound
    audio = np.stack((left, right), axis=1)
    peak = np.max(np.abs(audio))
    if peak > .95:
        audio *= .95 / peak
    pcm = np.round(audio * 32767).astype("<i2")
    OUTPUT.parent.mkdir(parents=True, exist_ok=True)
    with wave.open(str(OUTPUT), "wb") as wav:
        wav.setnchannels(2)
        wav.setsampwidth(2)
        wav.setframerate(RATE)
        wav.writeframes(pcm.tobytes())
    print(f"Wrote {OUTPUT} ({DURATION} seconds, {OUTPUT.stat().st_size} bytes)")
    print("Expected bass notes, one per second:", " ".join(name for _, name in BASS))


if __name__ == "__main__":
    main()
