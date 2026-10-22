"""Local, deterministic bass-stem and monophonic MIDI processing."""

import math
import os
from pathlib import Path
import struct
import wave

import numpy as np

MAX_SECONDS = 30
ANALYSIS_RATE = 11025
FRAME = 4096
HOP = 256


def safe_input(root, relative):
    root = Path(root).resolve()
    if Path(relative).is_absolute():
        raise ValueError("Input path escapes storage root")
    path = (root / relative).resolve()
    if root not in path.parents:
        raise ValueError("Input path escapes storage root")
    if not path.is_file():
        raise FileNotFoundError("Input file does not exist")
    return path


def extract_notes(audio, sample_rate):
    """Return (start seconds, end seconds, MIDI pitch) for a single bass line."""
    mono = np.asarray(audio, dtype=np.float32)
    if mono.ndim == 2:
        mono = mono.mean(axis=0)
    if mono.ndim != 1 or sample_rate <= 0:
        raise ValueError("Invalid audio samples")
    step = max(1, round(sample_rate / ANALYSIS_RATE))
    mono = mono[::step]
    rate = sample_rate / step
    duration = len(mono) / rate
    padded = np.pad(mono, (FRAME // 2, FRAME // 2))
    levels = []
    pitches = []
    times = []
    min_lag = max(1, int(rate / 400))
    max_lag = min(FRAME // 2, int(rate / 40))
    for start in range(0, len(mono), HOP):
        frame = padded[start:start + FRAME]
        if len(frame) < FRAME:
            frame = np.pad(frame, (0, FRAME - len(frame)))
        frame = frame - frame.mean()
        center = frame[FRAME // 2 - 256:FRAME // 2 + 256]
        level = float(np.sqrt(np.mean(center * center)))
        levels.append(level)
        times.append(start / rate)
        if level < 0.002:
            pitches.append(None)
            continue
        windowed = frame * np.hanning(FRAME)
        spectrum = np.fft.rfft(windowed, n=FRAME * 2)
        correlation = np.fft.irfft(spectrum * spectrum.conj())[:max_lag + 1]
        correlation /= np.maximum(1, FRAME - np.arange(len(correlation)))
        region = correlation[min_lag:max_lag + 1]
        peak = float(region.max())
        candidates = np.flatnonzero((region[1:-1] > region[:-2]) &
                                (region[1:-1] >= region[2:])) + min_lag + 1
        strong = [lag for lag in candidates if correlation[lag] >= 0.85 * peak]
        lag = strong[0] if strong else int(np.argmax(region)) + min_lag
        if correlation[lag] < 0.22 * correlation[0]:
            pitches.append(None)
            continue
        frequency = rate / lag
        pitch = round(69 + 12 * math.log2(frequency / 440))
        pitches.append(pitch if 28 <= pitch <= 67 else None)
    if not levels:
        return []
    gate = max(0.003, min(0.03, max(levels) * 0.12))
    pitches = [pitch if level >= gate else None
               for pitch, level in zip(pitches, levels)]
    for index in range(1, len(pitches) - 1):
        if pitches[index - 1] == pitches[index + 1] != pitches[index]:
            pitches[index] = pitches[index - 1]
    notes = []
    first = 0
    for index in range(1, len(pitches) + 1):
        if index < len(pitches) and pitches[index] == pitches[first]:
            continue
        pitch = pitches[first]
        if pitch is not None:
            begin = max(0.0, times[first] - HOP / (2 * rate))
            end = min(duration, times[index - 1] + HOP / (2 * rate))
            if end - begin >= 0.08:
                notes.append((begin, end, pitch))
        first = index
    return notes


def _variable_length(value):
    if value < 0:
        raise ValueError("Negative MIDI delta")
    result = [value & 0x7f]
    value >>= 7
    while value:
        result.insert(0, 0x80 | (value & 0x7f))
        value >>= 7
    return bytes(result)


def write_midi(path, notes):
    """Write format-0 MIDI at 120 BPM, 480 ticks per beat."""
    events = [(0, 0, b"\xff\x51\x03\x07\xa1\x20"),
              (0, 1, b"\xc0\x21")]
    for start, end, pitch in notes:
        events.append((round(start * 960), 2, bytes((0x90, pitch, 90))))
        events.append((round(end * 960), 1, bytes((0x80, pitch, 0))))
    track = bytearray()
    previous = 0
    for tick, _, event in sorted(events):
        track.extend(_variable_length(tick - previous))
        track.extend(event)
        previous = tick
    track.extend(b"\x00\xff\x2f\x00")
    path.write_bytes(b"MThd" + struct.pack(">IHHH", 6, 0, 1, 480) +
                     b"MTrk" + struct.pack(">I", len(track)) + track)


class TranscriptionProcessor:
    def __init__(self, root, separator=None):
        self.root = Path(root).resolve()
        self.separator = separator

    def _separator(self):
        if self.separator is None:
            from demucs.api import Separator
            self.separator = Separator(model="htdemucs", device="cpu", shifts=0,
                                       progress=False)
        return self.separator

    def process(self, relative, job_id):
        path = safe_input(self.root, relative)
        output = self.root / "results" / str(job_id)
        midi = output / "bass.mid"
        bass = output / "bass.wav"
        if midi.is_file() and bass.is_file():
            return path.stat().st_size, midi.relative_to(self.root).as_posix()

        import sphn
        import torch
        try:
            audio, rate = sphn.read(str(path), duration_sec=MAX_SECONDS + 1)
        except Exception as error:
            raise ValueError("Audio could not be decoded") from error
        if audio.size == 0 or rate <= 0:
            raise ValueError("Audio is empty")
        if audio.shape[-1] / rate > MAX_SECONDS:
            raise ValueError("Audio must be at most 30 seconds")
        separator = self._separator()
        from demucs.audio import convert_audio
        tensor = torch.from_numpy(np.ascontiguousarray(audio, dtype=np.float32))
        tensor = convert_audio(tensor, int(rate), separator.samplerate,
                               separator.audio_channels)
        with torch.inference_mode():
            _, stems = separator.separate_tensor(tensor, separator.samplerate)
        bass_audio = stems["bass"].detach().cpu().numpy()
        stem_rate = separator.samplerate
        notes = extract_notes(bass_audio, stem_rate)
        output.mkdir(parents=True, exist_ok=True)
        temporary_bass = output / "bass.wav.tmp"
        temporary_midi = output / "bass.mid.tmp"
        samples = np.clip(bass_audio.T, -1, 1)
        pcm = (samples * 32767).astype("<i2")
        with wave.open(str(temporary_bass), "wb") as wav:
            wav.setnchannels(bass_audio.shape[0])
            wav.setsampwidth(2)
            wav.setframerate(stem_rate)
            wav.writeframes(pcm.tobytes())
        write_midi(temporary_midi, notes)
        os.replace(temporary_bass, bass)
        os.replace(temporary_midi, midi)
        return path.stat().st_size, midi.relative_to(self.root).as_posix()
