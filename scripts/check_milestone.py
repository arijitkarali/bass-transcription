"""Run with API up and no worker running. Starts/stops only its own worker."""
import io
import json
import os
from pathlib import Path
import subprocess
import sys
import time
import urllib.request
import urllib.error
import uuid
import wave
import math
import pika

ROOT = Path(__file__).resolve().parents[1]
API = "http://127.0.0.1:8080/api/jobs"
sys.path.insert(0, str(ROOT / "worker"))
from main import connect, REQUESTS, EVENTS

def get(job_id):
    with urllib.request.urlopen(API + "/" + job_id, timeout=5) as response:
        return json.load(response)

def upload(data, filename="sample.wav"):
    boundary = uuid.uuid4().hex
    body = ("--" + boundary + '\r\nContent-Disposition: form-data; name="file"; filename="' + filename + '"\r\nContent-Type: application/octet-stream\r\n\r\n').encode() + data + ("\r\n--" + boundary + "--\r\n").encode()
    request = urllib.request.Request(API, data=body, headers={"Content-Type": "multipart/form-data; boundary=" + boundary})
    with urllib.request.urlopen(request, timeout=10) as response:
        assert response.status == 202
        return json.load(response)

def rejected_upload(data, filename, expected_status):
    try:
        upload(data, filename)
    except urllib.error.HTTPError as error:
        assert error.code == expected_status, error
    else:
        raise AssertionError(f"Expected HTTP {expected_status} for {filename}")

def wait(job_id, expected):
    deadline = time.monotonic() + 90
    while time.monotonic() < deadline:
        job = get(job_id)
        if job["status"] == expected: return job
        if job["status"] in ("COMPLETED", "FAILED"): raise AssertionError(job)
        time.sleep(.2)
    raise AssertionError("Timed out: " + str(job))

connection = connect()
channel = connection.channel()
assert channel.queue_declare(queue=REQUESTS, passive=True).method.consumer_count == 0, "Stop the project's worker first"
channel.confirm_delivery()
buffer = io.BytesIO()
with wave.open(buffer, "wb") as clip:
    clip.setnchannels(1); clip.setsampwidth(2); clip.setframerate(44100)
    import array
    samples = array.array("h", (round(18000 * math.sin(2 * math.pi * 110 * i / 44100)) for i in range(44100)))
    clip.writeframes(samples.tobytes())
data = buffer.getvalue()
rejected_upload(b"not audio", "sample.txt", 415)
rejected_upload(b"\0" * 20_000_000, "too-large.wav", 413)
success, missing, invalid = upload(data), upload(data), upload(b"not audio")
assert success["status"] == missing["status"] == invalid["status"] == "QUEUED"
# Delete only the disposable fixture created by this test.
(Path(os.environ["STORAGE_ROOT"]) / missing["inputPath"]).unlink()
worker = subprocess.Popen([str(ROOT / ".venv/bin/python"), "worker/main.py"], cwd=ROOT)
try:
    result = wait(success["id"], "COMPLETED")
    assert result["sizeBytes"] == len(data), result
    assert result["resultPath"] == f"results/{success['id']}/bass.mid"
    with urllib.request.urlopen(API + "/" + success["id"] + "/midi") as response:
        midi = response.read()
        assert response.status == 200 and midi.startswith(b"MThd") and b"\x90\x2d" in midi
    with urllib.request.urlopen(API + "/" + success["id"] + "/bass") as response:
        assert response.status == 200 and response.read(4) == b"RIFF"
    failure = wait(missing["id"], "FAILED")
    assert failure["errorMessage"], failure
    assert "decode" in wait(invalid["id"], "FAILED")["errorMessage"]
    # Redeliver the successful request; processing must not regress the saved state.
    channel.basic_publish("", REQUESTS, json.dumps(dict(version=1, jobId=success["id"], inputPath=success["inputPath"])), mandatory=True, properties=pika.BasicProperties(delivery_mode=2))
    channel.basic_publish("", EVENTS, json.dumps(dict(version=1, jobId=success["id"], status="FAILED", errorMessage="late duplicate")), mandatory=True, properties=pika.BasicProperties(delivery_mode=2))
    time.sleep(3)
    assert get(success["id"])["status"] == "COMPLETED"
    print(json.dumps(dict(success=result, missingFile=failure, invalidAudio="passed", midiDownload="passed", bassDownload="passed", uploadFilter="passed", duplicateEvents="passed"), indent=2))
finally:
    worker.terminate()
    worker.wait(timeout=10)
    connection.close()
