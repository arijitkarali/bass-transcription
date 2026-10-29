# Milestone 1 verification — 2026-09-30

Verified on this Mac using Temurin Java 17.0.8, Python 3.11.16 and the existing Rancher Docker context.

- Both project containers were running and healthy before implementation.
- Generated Maven wrapper preserved; backend packaged successfully with Spring Boot 4.1.1.
- API started on loopback port 8080, connected to PostgreSQL 17.11 and RabbitMQ, created/validated the schema, and started its event consumer.
- Java: 2 terminal-state tests passed; generated infrastructure context test skipped by default.
- Python: file size, missing-file, absolute-path, traversal and escaping-symlink checks passed.
- Live upload `723a8503-e239-4f52-b027-c741de5f83c3`: COMPLETED, `sizeBytes=1644`, matching the generated WAV.
- Live missing input `16a935bd-f713-4ba9-8ad6-32df002ec6e8`: FAILED, `Input file does not exist`.
- Repeated request plus late FAILED event left the successful job COMPLETED.
- The replacement Python 3.11 virtual environment installed Demucs 4.1.0, PyTorch 2.14.0 and NumPy 2.4.6; `pip check` found no broken requirements.
- Default `htdemucs` successfully separated the supplied 11.7-second WAV into `bass.wav` and `no_bass.wav`. The first CPU run took 14.0 seconds wall-clock including model download/startup; inference progress took about 4.6 seconds and peak resident memory was 1.86 GiB.
- PyTorch was built with MPS support but reported MPS unavailable on this host, so the benchmark explicitly used CPU. It demonstrates model access and execution only; bass-separation quality has not yet been evaluated against a labelled clip.

Not yet verified: deliberate broker outage and recovery, crash-injection durability, multi-instance concurrency, separation quality, pitch extraction, MIDI or frontend. The outbox and acknowledgement logic are implemented, but the live tests above are not proof of every crash scenario.

Commands and repeatable checks are in the root README. No unrelated containers were stopped or changed.

## Separation and MIDI work — 2026-10-02

- Python unit tests: generated 110 Hz and 146.83 Hz notes were identified as MIDI 45 and 50, with a silent gap; silence produced no notes. A fake separator test verified WAV/MIDI output and duplicate-request caching.
- Java unit tests passed after adding `resultPath` to completion events. The generated Spring context test remains skipped by default.
- Direct CPU Demucs run processed an existing 1,048,558-byte WAV in 4.89 seconds and produced a 16-bit bass WAV plus a valid format-0 MIDI file. That existing WAV was silent, so its MIDI contained no notes.
- Direct CPU Demucs run on a generated 110 Hz one-second tone produced a MIDI note-on for pitch 45. A longer generated two-note clip produced two note-on events.
- Local API and broker end-to-end check could not run: the API is stopped and Rancher Desktop's Docker socket is absent (`~/.rd/docker.sock`). No containers were started or stopped. The updated `scripts/check.sh` is ready when the project services are available.
- Musical quality on a labelled mixed recording remains unverified. The pitch tracker is monophonic and currently uses fixed velocity and tempo.

## User clip through the live project — 2026-10-10

- The supplied stereo PCM WAV `inputs/test for bass project.wav` is 29.35 seconds and 5,177,418 bytes. The API upload limit was raised from 5,000,000 to 6,000,000 bytes so a full clip under the 30-second processing cap can be accepted without trimming.
- Rebuilt the backend; three Java unit tests passed and the opt-in infrastructure context test remained skipped. PostgreSQL and RabbitMQ were healthy.
- Submitted the original file through `POST /api/jobs` with one local API and one worker. Job `6ce76e32-e148-4798-ade1-345f631b5329` reached `COMPLETED` in 14.683 seconds from upload start; observed `PROCESSING` to `COMPLETED` took about 12.25 seconds. Peak worker resident memory sampled every 0.3 seconds was 1,822.9 MiB.
- Both result endpoints returned HTTP 200 with valid MIDI/WAV signatures. The output contains a 512-byte MIDI file with 53 note-on events and a 5,177,372-byte bass WAV. Note accuracy was not compared with a ground-truth transcription.
- The API and worker started for this benchmark were stopped after verification. The project containers remain running.

## React frontend — 2026-10-10

- Built the local Vite/React frontend using the Codex desktop bundled Node runtime and pnpm 11.25.0. Dependencies are pinned and recorded in `frontend/pnpm-lock.yaml`; no global Node installation was required. The launcher adds bundled Node to PATH for package scripts. Only esbuild’s installation script is permitted in the pnpm workspace configuration.
- `./scripts/frontend.sh run build` passed. The generated JS bundle is about 231 kB (72 kB gzip), with about 6 kB of CSS.
- `./scripts/frontend.sh run test` passed all five tests: strict upload boundaries, multipart upload/polling/download flow and terminal polling stop, refresh restoration with worker failure, connection interruption with manual retry, and rejected upload handling.
- Headless installed Chrome exercised the app on `127.0.0.1:5173` against the existing local API, PostgreSQL, RabbitMQ, and Python worker. Generated `inputs/bass_treble_10s.wav` completed as job `ff85dada-52d0-4a89-9714-4377df36ec44`. Downloaded MIDI was 135 bytes with an `MThd` signature; bass WAV was 1,764,044 bytes with a `RIFF` signature. Reload restored the completed result from the saved job ID.
- Native browser playback of the completed bass stem advanced successfully and reported a 10-second duration. Clicking each download link produced the expected `.mid` and `.wav` filenames with no download failure.
- A second browser upload containing invalid bytes with a `.wav` filename reached FAILED, displayed the worker error, and offered no download links.
- Desktop (1280 px) and mobile (390 px) screenshots were visually inspected. Mobile had no horizontal overflow. No browser page errors were reported. Screenshots are retained in ignored `.cache/frontend-desktop.png`, `.cache/frontend-result.png`, and `.cache/frontend-mobile.png`.
- The frontend, API, and worker are left running for interactive review; existing infrastructure containers were inspected and left unchanged.

This milestone verifies the interface and end-to-end file flow. It does not establish transcription accuracy, broker outage recovery, or process crash durability. Those remain in the final recovery/quality milestone.

## Submissions dashboard — 2026-10-29

- Added paginated persisted history, original/numbered filename and job-ID search, status filters, global status counts, three-second refresh, result playback, individual downloads and terminal-job deletion. The UI can submit another upload while earlier jobs process.
- Recent activity shows the latest 30 completed/failed submissions across all pages and filters. Notification read state persists in the browser, and notices open the corresponding result.
- Bulk actions accept up to 20 jobs. MIDI-only, WAV-only and combined ZIP contents were verified. Deletion requires UI confirmation; missing or active jobs reject an entire selection. Only generated test records were deleted during verification.
- PostgreSQL migrations use Flyway. Backed up the existing project database to ignored `.cache/pre-dashboard-backup.sql` before applying V1/V2. All 26 existing submissions were preserved. An opt-in migration test also passed against disposable fresh and legacy schemas, including duplicate names and a repeat migration; its test schemas were removed.
- Java unit tests: 9 passed; the generated Spring context test and opt-in migration test skip by default. The migration test passed separately with `RUN_DASHBOARD_MIGRATION_TESTS=true`. Python: all 4 existing tests passed. Frontend: all 11 tests passed, and the production build succeeded.
- `scripts/check_dashboard.py` passed through the real frontend proxy, API, PostgreSQL, RabbitMQ and worker. It verified concurrent repeated-name allocation, collision with a name already containing a suffix, suffix reservations after deletion, search, filters, pagination, recent activity, all ZIP formats/signatures, missing/active selection rejection and deletion cleanup. Fixtures use a fresh unique basename per run; no existing submissions are changed.
- Live browser checks covered filename search, selection enabling bulk actions, a successful ZIP download, deletion confirmation/cancel, notification opening and opening a completed result from a notice. Desktop and mobile layouts were inspected; previews are kept in ignored `.cache/dashboard-desktop.png` and `.cache/dashboard-preview.png`.
- Filename allocation is persistent and case-insensitive: repeated uploads get `Song`, `Song (2)`, `Song (3)`. The same submission keeps its assigned download name; browser collision handling applies when downloaded repeatedly. This milestone does not add content-hash deduplication or active-job cancellation.
- API, frontend and worker remain running for review. Deliberate broker outages and crash-recovery checks remain pending.
