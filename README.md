# Local bass transcription

The local pipeline now uploads audio, separates its bass stem with Demucs, extracts a basic monophonic note line, and exports MIDI and WAV. Jobs and events pass through PostgreSQL and RabbitMQ. The React interface now supports upload, job polling, failure messages, bass playback and MIDI/WAV downloads. The submissions dashboard lists persisted uploads with live status, search, filters, pagination, recent notifications, ZIP downloads and deletion. Recovery hardening remains the next milestone.

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

   Open [Low End](http://127.0.0.1:5173). Choose or drop a WAV, AIF, or MP3 clip under 20 MB and at most 60 seconds, then select **Transcribe bass**. Keep the API and worker running. Once complete, listen to the bass stem or download MIDI/WAV. The current job is saved in this browser so refresh resumes polling. You can submit another clip while earlier jobs process. The selected job is saved in this browser; the dashboard loads all submissions from PostgreSQL after refresh.

The backend and worker launch scripts load `.env`. The API binds only to `127.0.0.1:8080`. Stop either process with Ctrl-C in its own terminal. Stop project infrastructure only if desired with `docker compose --env-file .env -p bass-transcription -f infra/compose.yaml stop`.

## Frontend checks

```sh
./scripts/frontend.sh run test
./scripts/frontend.sh run build
./scripts/frontend.sh run preview
```

The preview serves the production build at `http://127.0.0.1:4173`. Development and preview proxy `/api` to `http://127.0.0.1:8080`, including audio and download endpoints. If the API uses a different port, set `API_PROXY_TARGET` when starting the frontend. Both servers bind to loopback; no backend CORS change is needed. A separately hosted production build needs a same-origin `/api` reverse proxy. Vite preview is for local verification.

Polling runs every 1.5 seconds and stops on completion or failure. Temporary connection errors retry every 4 seconds without resubmitting the upload. **Check again** retries immediately. An unknown job stops automatic polling and can be cleared. Upload timeout messages explain that submission may have reached the API; upload is never automatically retried.

## Submissions dashboard

The library below the upload form refreshes every three seconds. Search by original filename, numbered download name or job ID, filter by status, and open any submission to inspect a failure or play its bass stem. The four status cards show totals across the whole library.

Select completed submissions and choose **Download ZIP** for MIDI, WAV or both. Select completed or failed submissions to delete their history, original audio and results after confirming. Queued and processing jobs cannot be deleted. Bulk actions support up to 20 jobs; selection applies to the current page. Individual downloads and deletion are also available on each row.

**Notifications** shows the latest 30 completed or failed jobs, independent of the current page and filter. Clicking a notification opens that submission. Read state is saved in this browser; notifications themselves come from persisted job records.

Repeated filenames reserve distinct download names: `Song.mid`, `Song (2).mid`, `Song (3).mid` (and the equivalent WAV names). Names stay reserved after deletion, including case-insensitive collisions. Each upload remains a separate job; identical file contents are not deduplicated. Re-downloading one job keeps its assigned name; the browser handles an existing file in the Downloads folder. Files on the server still use job IDs.

Schema changes run through Flyway at backend startup. Existing databases are baselined at version 0 and migrated without deleting submissions; fresh databases use the same migrations. Back up PostgreSQL before applying future migrations. `schema.sql` is no longer used to initialize the database.

Dashboard API:

- `GET /api/jobs?page=0&size=10&status=COMPLETED&search=Song` returns newest-first items, the filtered total, global status counts and recent activity. Status and search are optional; size is 1–50.
- `POST /api/jobs/downloads?format=both` accepts `{ "ids": ["UUID", "UUID"] }` and returns one ZIP. Format is `both`, `midi` or `bass`. All selected jobs must be complete and have their result files.
- `POST /api/jobs/delete` accepts the same selection and deletes only terminal jobs. A missing or active job rejects the whole selection. Files are removed after the database transaction; any cleanup failures are returned as warnings.

With the API, frontend proxy, worker and infrastructure running, verify the dashboard using generated fixtures:

```sh
.venv/bin/python scripts/check_dashboard.py
```

The check tests concurrent filename allocation, filters, pagination, ZIP contents, active-job protection and deletion. It removes only its own generated submissions. Name reservations intentionally remain.

To verify migrations in disposable schemas within the project database:

```sh
set -a
. ./.env
set +a
RUN_DASHBOARD_MIGRATION_TESTS=true backend/mvnw -f backend/pom.xml -B -Dmaven.repo.local="$PWD/.cache/m2" -Dtest=DashboardMigrationTest test
```

## Try an upload

```sh
curl -i -H 'Expect:' -F 'file=@/absolute/path/to/clip.wav' http://127.0.0.1:8080/api/jobs
curl http://127.0.0.1:8080/api/jobs/JOB_ID_FROM_UPLOAD
```

Upload returns HTTP 202 and a Location header. Poll that URL. Jobs move through QUEUED → PROCESSING → COMPLETED or FAILED; a quick worker may finish between polls. Uploads must be smaller than 20 MB and have a `.wav`, `.aif`, or `.mp3` filename (case-insensitive); unsupported types return 415 and oversized files return 413. Audio must decode and be at most 60 seconds; otherwise the job fails with a message. Filenames are generated UUIDs, never client-supplied paths. Unknown job IDs return 404; empty uploads return 400.

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
