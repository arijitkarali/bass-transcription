"""Live dashboard verification. Requires API, frontend proxy, infrastructure and worker.

Creates generated fixtures with a unique name; deletes only those fixtures afterwards.
Does not restart services or modify existing submissions.
"""
import array
from concurrent.futures import ThreadPoolExecutor
import io
import json
import math
import os
from pathlib import Path
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid
import wave
import zipfile

API = os.getenv('DASHBOARD_API', 'http://127.0.0.1:5173/api/jobs')
created = []
STORAGE = Path(os.getenv('STORAGE_ROOT', Path(__file__).resolve().parents[1] / '.storage'))
stem = 'dashboard-check-' + uuid.uuid4().hex[:10]


def request(path='', data=None, method='GET', expected=200):
    headers = {}
    if data is not None:
        headers['Content-Type'] = 'application/json'
        data = json.dumps(data).encode()
    req = urllib.request.Request(API + path, data=data, method=method, headers=headers)
    try:
        response = urllib.request.urlopen(req, timeout=30)
    except urllib.error.HTTPError as error:
        assert error.code == expected, (error.code, error.read().decode())
        return None
    assert response.status == expected
    payload = response.read()
    if response.headers.get_content_type() == 'application/zip':
        return payload
    return json.loads(payload)


def upload(name, payload):
    boundary = uuid.uuid4().hex
    body = (f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="{name}.wav"\r\n'
            'Content-Type: audio/wav\r\n\r\n').encode() + payload + f'\r\n--{boundary}--\r\n'.encode()
    req = urllib.request.Request(API, data=body, headers={'Content-Type': f'multipart/form-data; boundary={boundary}'})
    with urllib.request.urlopen(req, timeout=20) as response:
        assert response.status == 202
        job = json.load(response)
        created.append(job)
        return job


def wait_all():
    deadline = time.monotonic() + 120
    while time.monotonic() < deadline:
        jobs = [request('/' + job['id']) for job in created]
        if all(job['status'] in ('COMPLETED', 'FAILED') for job in jobs):
            return jobs
        time.sleep(.5)
    raise AssertionError('Fixtures did not finish within 120 seconds')


clip = io.BytesIO()
with wave.open(clip, 'wb') as wav:
    wav.setnchannels(1)
    wav.setsampwidth(2)
    wav.setframerate(11025)
    wav.writeframes(array.array('h', (round(12000 * math.sin(2 * math.pi * 110 * i / 11025)) for i in range(11025))).tobytes())

try:
    initial = request('?size=1')
    assert len(initial['items']) <= 1
    assert sum(initial['stats'].values()) == initial['total']
    with ThreadPoolExecutor(max_workers=3) as pool:
        duplicates = list(pool.map(lambda _: upload(stem, clip.getvalue()), range(3)))
    assert {job['downloadName'] for job in duplicates} == {stem, stem + ' (2)', stem + ' (3)'}
    extra = upload(stem + ' (2)', clip.getvalue())
    assert extra['downloadName'] == stem + ' (2) (2)'
    ids = [job['id'] for job in created]
    current = [request('/' + id) for id in ids]
    if any(job['status'] not in ('COMPLETED', 'FAILED') for job in current):
        request('/delete', {'ids': ids}, 'POST', 409)
        assert all(request('/' + id) is not None for id in ids)
    invalid = upload(stem + '-invalid', b'not audio')
    terminal = wait_all()
    completed = [job for job in terminal if job['status'] == 'COMPLETED']
    assert len(completed) == 4 and next(job for job in terminal if job['id'] == invalid['id'])['status'] == 'FAILED'
    filtered = request('?' + urllib.parse.urlencode({'search': stem, 'status': 'COMPLETED', 'size': 2}))
    assert filtered['total'] == 4 and len(filtered['items']) == 2
    page_two = request('?' + urllib.parse.urlencode({'search': stem, 'status': 'COMPLETED', 'size': 2, 'page': 1}))
    assert not {job['id'] for job in filtered['items']} & {job['id'] for job in page_two['items']}
    assert any(job['id'] == invalid['id'] for job in filtered['activity'])
    numbered = request('?' + urllib.parse.urlencode({'search': stem + ' (3)'}))
    assert numbered['total'] == 1 and numbered['items'][0]['downloadName'] == stem + ' (3)'
    for format, extensions in [('both', ['.mid', '.wav']), ('midi', ['.mid']), ('bass', ['.wav'])]:
        payload = request('/downloads?format=' + format, {'ids': [job['id'] for job in completed]}, 'POST')
        with zipfile.ZipFile(io.BytesIO(payload)) as archive:
            expected = {job['downloadName'] + ext for job in completed for ext in extensions}
            assert set(archive.namelist()) == expected
            assert archive.testzip() is None
            for name in archive.namelist():
                assert archive.read(name).startswith(b'MThd' if name.endswith('.mid') else b'RIFF')
    request('/downloads', {'ids': [invalid['id']]}, 'POST', 409)
    request('/downloads', {'ids': []}, 'POST', 400)
    request('/delete', {'ids': [ids[0], str(uuid.uuid4())]}, 'POST', 404)
    assert request('/' + ids[0])['status'] == 'COMPLETED'
    # The test deletes only the generated fixtures it created above.
    result = request('/delete', {'ids': [job['id'] for job in created]}, 'POST')
    assert not result['cleanupWarnings']
    for job in created:
        request('/' + job['id'], expected=404)
        assert not (STORAGE / job['inputPath']).exists()
        assert not (STORAGE / 'results' / job['id']).exists()
    assert request('?' + urllib.parse.urlencode({'search': stem}))['total'] == 0
    later = upload(stem, clip.getvalue())
    assert later['downloadName'] == stem + ' (4)'
    created = [later]
    wait_all()
    assert request('/delete', {'ids': [later['id']]}, 'POST')['cleanupWarnings'] == []
    created = []
    print(json.dumps({'concurrentNumbering': 'passed', 'nameReservationsAfterDelete': 'passed', 'paginationAndFilters': 'passed',
                      'activity': 'passed', 'bulkZipBothMidiWav': 'passed', 'deleteActiveProtection': 'passed',
                      'deleteAtomicityAndCleanup': 'passed', 'fixturePrefix': stem}, indent=2))
finally:
    # Best effort cleanup on failed checks; leave active jobs intact.
    for job in created:
        try:
            saved = request('/' + job['id'], expected=404)
        except AssertionError:
            saved = request('/' + job['id'])
        if saved and saved['status'] in ('COMPLETED', 'FAILED'):
            request('/delete', {'ids': [job['id']]}, 'POST')
