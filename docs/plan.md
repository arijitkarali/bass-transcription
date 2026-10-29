# Three-week implementation plan

This is a delivery plan, not an unattended scheduled task. Each stage ends with a runnable demonstration and recorded results.

| Period | Deliverable | Exit check |
| --- | --- | --- |
| Week 1, initial milestone | Local API, persistence, outbox, RabbitMQ and file-size worker | Upload succeeds; missing file fails; duplicate events preserve terminal status |
| Week 1, remaining — completed | Evaluate bass separation library and supported isolated Python environment | Demucs ran on this laptop; timing and memory are recorded in verification notes |
| Week 2 — completed | Separation adapter, basic monophonic pitch tracking, simplified notes and MIDI | Synthetic note tests and live API-to-worker upload/download passed; real-song note accuracy still needs review |
| Week 3, first half — completed | React upload, polling, clear failure state, bass playback and result download | Production build and five frontend tests passed; live browser upload-to-download, refresh, invalid audio and mobile layout passed using the bundled Node runtime |
| Week 3, second half | Recovery checks, limits, documentation and repeatable demo | Broker interruption/recovery, process restart, invalid audio and full demo; document transcription quality and runtime |
| Follow-on milestone — completed | Submissions dashboard, notifications and bulk actions | Persisted history, status filters, search, pagination, MIDI/WAV ZIPs, confirmed terminal-job deletion and numbered names verified |

Demucs 4.1.0 is installed in the isolated Python 3.11 environment and runs on CPU. Its original repository is [archived](https://github.com/facebookresearch/demucs), so replacement or maintenance ownership should be evaluated before production use. The worker limits uploads to 60 seconds of decoded audio. Do not replace macOS Python or infer GPU availability from Rancher VM resources.

MIDI is the primary musical export. MusicXML/PDF remains a stretch goal. LangChain, hosted LLMs, authentication and cloud/Kubernetes deployment are deferred. A local submissions dashboard is implemented. Recovery hardening remains pending.

## Pending filename UI tasks

- [x] Validate filename length in the UI before upload. Check the filename without its extension against the `jobs.user_file_name` limit of 255 characters and display a clear error when it exceeds that limit. Backend validation also rejects names over the limit.
- [x] Download filenames use the stored `userFileName`: `<name>.mid` and `<name>.wav`, preserving the uploaded base name. The backend sets `Content-Disposition` for the UI's direct download links, with a job-ID fallback for older jobs without a filename.

## Submissions dashboard

Implemented with persisted history, recent notifications and bulk actions. The selected job is still restored independently after refresh.

- [x] Add a paginated jobs-list endpoint, ordered by submission time with newest first.
- [x] Build a submissions view showing filename, submission time and job status. Use a job-ID fallback for records without `userFileName`.
- [x] Allow opening a submission to view status, failure details, bass playback and MIDI/WAV downloads when completed. Use persisted, numbered download names.
- [x] Refresh queued and processing submissions without resubmitting them; provide loading, empty and API-error states.
- [x] Verify pagination, older records without filenames, failed and active jobs, completed downloads, and history persistence after browser refresh.

- [x] Recent activity panel with completion/failure notifications and browser-persisted read state.
- [x] ZIP downloads for up to 20 completed submissions, with MIDI, WAV or both.
- [x] Confirmed deletion of up to 20 completed/failed submissions and their files; active jobs are protected.
- [x] Unique, case-insensitive download-name reservations survive deletion and concurrent repeated uploads.
