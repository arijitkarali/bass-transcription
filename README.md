# Local bass transcription

The local pipeline now uploads audio, separates its bass stem with Demucs, extracts a basic monophonic note line, and exports MIDI and WAV. Jobs and events pass through PostgreSQL and RabbitMQ. The React interface now supports upload, job polling, failure messages, bass playback and MIDI/WAV downloads. Recovery hardening remains the next milestone.

## Local setup

Java 17, Python 3.11, and Node.js 22.12+ are used for this milestone. The frontend launcher uses your Node/pnpm installation or the Codex desktop’s bundled runtime on this Mac. PostgreSQL and RabbitMQ use the existing Rancher Desktop Moby engine. Do not reset Rancher or stop unrelated containers.

1. Copy `.env.example` to `.env` if `.env` does not already exist. Set the passwords to the values used to initialize the existing project containers, and set `STORAGE_ROOT` to an absolute project-local `.storage` directory. The initial setup has already created this ignored file on this machine. Changing environment variables does not rotate credentials in existing persistent volumes.
2. From the repository root, inspect infrastructure:

   ```sh
   docker compose --env-file .env -p bass-transcription -f infra/compose.yaml ps
   ```

   To start only this project's services, use the same command with `up -d` instead of `ps`. Existing images, volume names and loopback bindings are preserved.
3. Build the API:

   ```sh
   cd backend
   ./mvnw -B -Dmaven.repo.local=../.cache/m2 package
   cd ..
   ```

   The preserved Maven wrapper downloads Maven and dependencies; a system Maven installation is unnecessary. Spring Boot 4.1.1 runs on Java 17. Initializr returned a `.RELEASE` suffix that Maven Central rejected; the POM uses the verified `4.1.1` coordinate.
4. Set up the Python client in an isolated environment:

   ```sh
   ~/.local/bin/uv venv --python 3.11 .venv
   .venv/bin/python -m pip install -r worker/requirements.txt
   ```

   This does not replace system Python. The pinned CPU stack has been exercised on this Mac; Demucs downloads its model on first use.
5. Start the API in one terminal:

   ```sh
   ./scripts/backend.sh
   ```

6. Start the worker in another terminal:

   ```sh
   ./scripts/worker.sh
   ```

7. Install and start the React frontend in a third terminal:

   ```sh
   ./scripts/frontend.sh install --frozen-lockfile
   ./scripts/frontend.sh run dev
   ```

   Open [Low End](http://127.0.0.1:5173). Choose or drop a WAV, AIF, or MP3 clip under 6 MB and at most 30 seconds, then select **Transcribe bass**. Keep the API and worker running. Once complete, listen to the bass stem or download MIDI/WAV. The current job is saved in this browser so refresh resumes polling. A new transcription clears that saved reference; it does not remove backend files or cancel a job.

The backend and worker launch scripts load `.env`. The API binds only to `127.0.0.1:8080`. Stop either process with Ctrl-C in its own terminal. Stop project infrastructure only if desired with `docker compose --env-file .env -p bass-transcription -f infra/compose.yaml stop`.

## Frontend checks

```sh
./scripts/frontend.sh run test
./scripts/frontend.sh run build
./scripts/frontend.sh run preview
```

The preview serves the production build at `http://127.0.0.1:4173`. Development and preview proxy `/api` to `http://127.0.0.1:8080`, including audio and download endpoints. If the API uses a different port, set `API_PROXY_TARGET` when starting the frontend. Both servers bind to loopback; no backend CORS change is needed. A separately hosted production build needs a same-origin `/api` reverse proxy. Vite preview is for local verification.

Polling runs every 1.5 seconds and stops on completion or failure. Temporary connection errors retry every 4 seconds without resubmitting the upload. **Check again** retries immediately. An unknown job stops automatic polling and can be cleared. Upload timeout messages explain that submission may have reached the API; upload is never automatically retried.

## Try an upload

```sh
curl -i -H 'Expect:' -F 'file=@/absolute/path/to/clip.wav' http://127.0.0.1:8080/api/jobs
curl http://127.0.0.1:8080/api/jobs/JOB_ID_FROM_UPLOAD
```

Upload returns HTTP 202 and a Location header. Poll that URL. Jobs move through QUEUED → PROCESSING → COMPLETED or FAILED; a quick worker may finish between polls. Uploads must be smaller than 6 MB and have a `.wav`, `.aif`, or `.mp3` filename (case-insensitive); unsupported types return 415 and oversized files return 413. Audio must decode and be at most 30 seconds; otherwise the job fails with a message. Filenames are generated UUIDs, never client-supplied paths. Unknown job IDs return 404; empty uploads return 400.

When a job reaches `COMPLETED`, download `GET /api/jobs/JOB_ID/midi` or `GET /api/jobs/JOB_ID/bass`. The first is a format-0 MIDI file; the second is a 16-bit WAV bass stem. `resultPath` points to the MIDI file under `.storage/results/JOB_ID/`. The MIDI extractor assumes one note at a time and uses fixed velocity and 120 BPM; it does not recover expressive timing, overlapping notes, or tempo from the recording. Silence produces an empty MIDI track.

## Verification

With the API running and the worker stopped:

```sh
./scripts/check.sh
```

This uploads a generated 110 Hz tone plus missing and invalid-audio fixtures, starts its own worker, checks MIDI/WAV downloads and duplicate delivery, and stops that worker. It leaves small job records/files for inspection. Run it while no other user is submitting work: its worker consumes the project's request queue. It does not stop any container.

```sh
.venv/bin/python -m unittest discover -s worker
cd backend
./mvnw -B -Dmaven.repo.local=../.cache/m2 test
```

The generated Spring context test is opt-in (`RUN_INFRA_TESTS=true`, exported `.env`, and a free API port). Default Java tests exercise terminal-state idempotency without infrastructure; the live check verifies real PostgreSQL/RabbitMQ wiring.

See [architecture](docs/architecture.md), [verification evidence](docs/verification.md), and the [three-week plan](docs/plan.md).
