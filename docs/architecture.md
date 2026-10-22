# Architecture and decisions

## Ownership and contracts

Spring Boot owns job records and handles HTTP. Spring Data JPA provides the Repository pattern. A Python process performs separation and transcription through `TranscriptionProcessor`. RabbitMQ carries work requests and status events. These are point-to-point durable queues with competing consumers, not a broadcast pub/sub exchange. A topic exchange with independent subscriber queues can be added when there is an actual second subscriber.

All messages are UTF-8 JSON with `version: 1`. Request queue `bass.jobs.requests` receives:

```json
{"version":1,"jobId":"UUID","inputPath":"inputs/UUID.audio"}
```

Event queue `bass.jobs.events` receives `jobId`, `version`, and `status`. PROCESSING has no result fields. COMPLETED requires nonnegative `sizeBytes` and `resultPath` equal to `results/JOB_ID/bass.mid`. FAILED requires `errorMessage` (maximum 2,000 characters). The worker never accesses PostgreSQL. Paths are relative to the identical absolute `STORAGE_ROOT` used by both processes. The worker rejects absolute paths, traversal and symlinks escaping that root.

The worker decodes at most 31 seconds of audio and rejects clips over 30 seconds. It runs the pinned `htdemucs` model on CPU with random shifts disabled, writes `bass.wav`, estimates one bass pitch per frame using autocorrelation, then writes format-0 `bass.mid`. It publishes a terminal event only after both files are saved; the MIDI file is the final completion marker. Repeated requests reuse complete output files. The API provides `/api/jobs/JOB_ID/midi` and `/api/jobs/JOB_ID/bass` once the job is complete.

## Reliability

The upload is written before the job is saved. One database transaction inserts the job and its outbox row. A scheduled publisher reads up to 20 pending rows every two seconds, sends persistent messages with mandatory routing, waits for publisher confirmation, then deletes each outbox row. If RabbitMQ is unavailable, rows remain for retry. A crash after publishing but before deleting can produce a duplicate: delivery is at least once.

The worker uses prefetch 1, manual acknowledgements and publisher confirms. It acknowledges the request only after its terminal event is accepted by RabbitMQ. Disconnects leave unacknowledged work eligible for redelivery. Events are acknowledged after the Spring service's database transaction commits. A database access failure requests redelivery; malformed messages go to durable `bass.jobs.dead` for manual inspection. There is no automatic dead-letter replay yet.

The API locks each job while applying events. COMPLETED and FAILED are terminal; late or repeated events cannot overwrite them. Reprocessing reuses complete deterministic outputs and can safely overwrite a partially written result. Run one API and one worker for this milestone: multi-instance outbox claiming is deferred. RabbitMQ volumes preserve messages across normal restarts; this local single-node deployment is not highly available.

## Storage and schema limits

`schema.sql` creates only the two project tables if absent; Hibernate validates the schema instead of silently modifying it. Add versioned migrations before changing an existing schema. Filesystem writes and database commits are not one transaction: a failed job insert may leave an orphan file. It is deliberately retained rather than risking removal after an ambiguous commit. Automated retention, quotas, orphan cleanup and retry UI are future work. Do not delete queued inputs except in the deliberate failure check.

Credentials moved from Compose literals into ignored `.env`; `.env.example` has no passwords. Existing volume credentials are unchanged. `.storage`, `.venv`, `.cache`, build output and Python caches are ignored. The Demucs model is downloaded on first use and then cached locally. The [original Demucs repository](https://github.com/facebookresearch/demucs) is archived, so it should be reconsidered before production use. All new verification uses generated audio.

## Local React interface

The Vite/React app lives in `frontend/` and uses relative `/api/jobs` URLs. Vite proxies uploads, polling, audio playback, and downloads to the loopback Spring API; the backend contract is unchanged. The production build requires the same `/api` routing when hosted elsewhere. No external font or asset service is used.

The browser validates extension, nonempty uploads, and the strict 6,000,000-byte boundary before sending multipart data. Decoding and the 30-second cap remain authoritative worker checks. One job is active in the UI at a time. Its ID and display filename are stored in localStorage, allowing refresh to re-fetch authoritative job state. Storage unavailability does not prevent use within the current page. Polling requests are bounded and cancelled when the job changes or the component unmounts. Terminal jobs stop polling. Temporary failures retain the job and retry without posting another upload; 404 stops automatic retries. Clearing a job only clears browser state.

The UI exposes the worker’s failure message, hides downloads until completion, and offers native bass audio controls. The waveform is decorative and does not depict measured audio or percentage progress. Recovery, retention, explicit job cancellation, and musical accuracy evaluation remain separate work.
