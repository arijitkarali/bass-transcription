# Three-week implementation plan

This is a delivery plan, not an unattended scheduled task. Each stage ends with a runnable demonstration and recorded results.

| Period | Deliverable | Exit check |
| --- | --- | --- |
| Week 1, initial milestone | Local API, persistence, outbox, RabbitMQ and file-size worker | Upload succeeds; missing file fails; duplicate events preserve terminal status |
| Week 1, remaining — completed | Evaluate bass separation library and supported isolated Python environment | Demucs ran on this laptop; timing and memory are recorded in verification notes |
| Week 2 — completed | Separation adapter, basic monophonic pitch tracking, simplified notes and MIDI | Synthetic note tests and live API-to-worker upload/download passed; real-song note accuracy still needs review |
| Week 3, first half — completed | React upload, polling, clear failure state, bass playback and result download | Production build and five frontend tests passed; live browser upload-to-download, refresh, invalid audio and mobile layout passed using the bundled Node runtime |
| Week 3, second half | Recovery checks, limits, documentation and repeatable demo | Broker interruption/recovery, process restart, invalid audio and full demo; document transcription quality and runtime |
| Follow-on milestone — planned | Dashboard for existing submissions | Browse persisted jobs after refresh; view status and failure details; play bass and download results for completed jobs |

Demucs 4.1.0 is installed in the isolated Python 3.11 environment and runs on CPU. Its original repository is [archived](https://github.com/facebookresearch/demucs), so replacement or maintenance ownership should be evaluated before production use. The worker limits uploads to 30 seconds of decoded audio. Do not replace macOS Python or infer GPU availability from Rancher VM resources.

MIDI is the primary musical export. MusicXML/PDF remains a stretch goal. LangChain, hosted LLMs, authentication and cloud/Kubernetes deployment are deferred. A local dashboard for existing submissions is now a planned follow-on milestone.

## Pending filename UI tasks

- [ ] Validate filename length in the UI before upload. Check the filename without its extension against the `jobs.user_file_name` limit of 255 characters and display a clear error when it exceeds that limit. Backend length validation is currently deferred.
- [ ] Reconcile download filenames in the UI using the stored `userFileName`: `<name>_bass.mid` and `<name>_bass.wav`. Fall back to the job ID for older jobs without a filename. Account for the existing backend `Content-Disposition` filename, which takes precedence over a direct link's `download` filename; verify both downloads after implementation.

## Planned submissions dashboard

The current UI restores only the last saved job in this browser. Browsing existing submissions is not implemented; the dashboard will read persisted jobs from PostgreSQL through the API.

- [ ] Add a paginated jobs-list endpoint, ordered by submission time with newest first.
- [ ] Build a submissions view showing filename, submission time and job status. Use a job-ID fallback for records without `userFileName`.
- [ ] Allow opening a submission to view status, failure details, bass playback and MIDI/WAV downloads when completed. Reuse the planned download naming behavior.
- [ ] Refresh queued and processing submissions without resubmitting them; provide loading, empty and API-error states.
- [ ] Verify pagination, older records without filenames, failed and active jobs, completed downloads, and history persistence after browser refresh.
