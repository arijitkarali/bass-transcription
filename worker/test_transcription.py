import tempfile
import unittest
import uuid
import wave
import struct
from pathlib import Path

import numpy as np
import torch

from processor import TranscriptionProcessor, extract_notes, safe_input


RATE = 11025


def tone(frequency, seconds):
    time = np.arange(round(RATE * seconds)) / RATE
    return (0.35 * np.sin(2 * np.pi * frequency * time)).astype(np.float32)


def midi_events(payload):
    assert payload[:4] == b"MThd"
    assert struct.unpack(">IHHH", payload[4:14]) == (6, 0, 1, 480)
    assert payload[14:18] == b"MTrk"
    length = struct.unpack(">I", payload[18:22])[0]
    track = payload[22:22 + length]
    assert len(track) == length and len(payload) == 22 + length
    events = []
    tick = 0
    index = 0
    while index < len(track):
        delta = 0
        while True:
            value = track[index]
            index += 1
            delta = (delta << 7) | (value & 0x7f)
            if not value & 0x80:
                break
        tick += delta
        status = track[index]
        index += 1
        if status == 0xff:
            kind = track[index]
            size = track[index + 1]
            index += 2 + size
            if kind == 0x2f:
                break
        else:
            size = 1 if status & 0xf0 == 0xc0 else 2
            events.append((tick, status, *track[index:index + size]))
            index += size
    return events


class FakeSeparator:
    samplerate = RATE
    audio_channels = 1

    def __init__(self):
        self.calls = 0

    def separate_tensor(self, audio, rate):
        self.calls += 1
        return audio, {"bass": audio}


class TranscriptionTests(unittest.TestCase):
    def test_known_notes_and_silence(self):
        audio = np.concatenate((tone(110, .7), np.zeros(round(RATE * .25)),
                                tone(146.83, .7)))
        notes = extract_notes(audio, RATE)
        self.assertEqual([pitch for _, _, pitch in notes], [45, 50])
        self.assertAlmostEqual(notes[0][0], 0, delta=.05)
        self.assertAlmostEqual(notes[0][1], .7, delta=.08)
        self.assertAlmostEqual(notes[1][0], .95, delta=.08)
        self.assertAlmostEqual(notes[1][1], 1.65, delta=.08)
        self.assertEqual(extract_notes(np.zeros(RATE), RATE), [])

    def test_result_files_and_cache(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "inputs").mkdir()
            audio = tone(110, .7)
            source = root / "inputs" / "clip.wav"
            with wave.open(str(source), "wb") as wav:
                wav.setnchannels(1)
                wav.setsampwidth(2)
                wav.setframerate(RATE)
                wav.writeframes((audio * 32767).astype("<i2").tobytes())
            separator = FakeSeparator()
            processor = TranscriptionProcessor(root, separator)
            job_id = uuid.uuid4()
            size, result = processor.process("inputs/clip.wav", job_id)
            self.assertEqual(size, source.stat().st_size)
            self.assertEqual(result, f"results/{job_id}/bass.mid")
            midi = root / result
            payload = midi.read_bytes()
            events = midi_events(payload)
            self.assertIn((0, 0x90, 45, 90), events)
            self.assertTrue(any(event[1:3] == (0x80, 45) and event[0] > 500
                                for event in events))
            self.assertTrue((midi.parent / "bass.wav").is_file())
            self.assertEqual(processor.process("inputs/clip.wav", job_id), (size, result))
            self.assertEqual(separator.calls, 1)

    def test_missing_and_escaping_paths(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            with self.assertRaises(FileNotFoundError):
                safe_input(root, "missing")
            with self.assertRaises(ValueError):
                safe_input(root, "../outside")
            with self.assertRaises(ValueError):
                safe_input(root, str(root / "clip"))
            (root / "link").symlink_to(root.parent)
            with self.assertRaises(ValueError):
                safe_input(root, "link/outside")

    def test_invalid_audio_and_duration_limit(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            (root / "inputs").mkdir()
            processor = TranscriptionProcessor(root, FakeSeparator())
            (root / "inputs" / "invalid.wav").write_bytes(b"not audio")
            with self.assertRaisesRegex(ValueError, "could not be decoded"):
                processor.process("inputs/invalid.wav", uuid.uuid4())
            with wave.open(str(root / "inputs" / "long.wav"), "wb") as wav:
                wav.setnchannels(1)
                wav.setsampwidth(2)
                wav.setframerate(8000)
                wav.writeframes(b"\0\0" * (8000 * 31))
            with self.assertRaisesRegex(ValueError, "at most 30 seconds"):
                processor.process("inputs/long.wav", uuid.uuid4())
