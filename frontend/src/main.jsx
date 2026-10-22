import React, { useEffect, useRef, useState } from 'react';
import { createRoot } from 'react-dom/client';
import { isTerminal, readSavedJob, requestJob, saveJob, validateFile } from './api';
import './styles.css';

const labels = { QUEUED: 'Waiting for the worker', PROCESSING: 'Finding your bass line', COMPLETED: 'Your bass line is ready', FAILED: 'We couldn’t transcribe this clip' };
export function App() {
  const [saved] = useState(readSavedJob);
  const [jobId, setJobId] = useState(saved?.id || null);
  const [fileName, setFileName] = useState(saved?.fileName || 'Audio clip');
  const [file, setFile] = useState(null);
  const [job, setJob] = useState(null);
  const [uploading, setUploading] = useState(false);
  const [error, setError] = useState('');
  const [pollError, setPollError] = useState('');
  const [retry, setRetry] = useState(0);
  const [dragging, setDragging] = useState(false);
  const input = useRef(null);
  const busy = uploading || Boolean(jobId && !isTerminal(job));

  useEffect(() => {
    if (!jobId || isTerminal(job)) return;
    const controller = new AbortController();
    let timer;
    async function poll() {
      try {
        const next = await requestJob(`/api/jobs/${jobId}`, { signal: AbortSignal.any([controller.signal, AbortSignal.timeout(15_000)]) });
        if (controller.signal.aborted) return;
        setJob(next); setPollError('');
        if (!isTerminal(next)) timer = setTimeout(poll, 1500);
      } catch (err) {
        if (controller.signal.aborted) return;
        setPollError(err.status === 404 ? err.message : 'Connection interrupted. Your job is saved; we’ll keep checking. Make sure the local API is running.');
        if (err.status !== 404) timer = setTimeout(poll, 4000);
      }
    }
    poll();
    return () => { controller.abort(); clearTimeout(timer); };
  }, [jobId, job?.status, retry]);

  function choose(next) {
    if (busy) return;
    const message = validateFile(next);
    setError(message); setFile(message ? null : next);
  }
  async function submit(event) {
    event.preventDefault();
    const message = validateFile(file);
    if (message || busy) { setError(message); return; }
    setUploading(true); setError(''); setPollError('');
    try {
      const body = new FormData(); body.append('file', file);
      const next = await requestJob('/api/jobs', { method: 'POST', body });
      setFileName(file.name); setJob(next); setJobId(next.id);
      saveJob({ id: next.id, fileName: file.name });
    } catch (err) {
      setError(err.name === 'TimeoutError' ? 'The upload timed out. It may have reached the API; check the backend before trying again.' : err.message === 'Failed to fetch' ? 'Cannot reach the local API. Start the backend and try again.' : err.message);
    } finally { setUploading(false); }
  }
  function reset() {
    setJobId(null); setJob(null); setFile(null); setError(''); setPollError(''); saveJob(null);
    if (input.current) input.current.value = '';
  }
  const step = job?.status === 'COMPLETED' ? 3 : job?.status === 'PROCESSING' ? 2 : jobId ? 1 : 0;
  return <div className="app-shell">
    <header><a className="brand" href="/" aria-label="Low End home"><span className="brand-mark">≋</span> LOW END<span className="brand-sub"> / bass transcription</span></a><span className="local-badge"><i /> LOCAL SESSION</span></header>
    <main>
      <section className="intro"><div className="eyebrow">FROM RECORDING TO RIFF</div><h1>Hear the bass.<br /><em>Keep the notes.</em></h1><p>Turn a short audio clip into an isolated bass stem and a MIDI line you can take straight to your DAW.</p></section>
      <div className="workspace">
        <section className="panel upload-panel" aria-labelledby="upload-heading">
          <div className="panel-heading"><span className="number">01</span><h2 id="upload-heading">Your audio</h2><span className="small-tag">UP TO 30 SEC</span></div>
          <form onSubmit={submit}>
            <input ref={input} id="audio-file" type="file" accept=".wav,.aif,.mp3" disabled={busy} onChange={event => choose(event.target.files?.[0])} />
            <label htmlFor="audio-file" className={`drop-zone ${dragging ? 'dragging' : ''} ${busy ? 'disabled' : ''}`} onDragOver={event => { event.preventDefault(); if (!busy) setDragging(true); }} onDragLeave={() => setDragging(false)} onDrop={event => { event.preventDefault(); setDragging(false); choose(event.dataTransfer.files?.[0]); }}>
              <span className="upload-icon" aria-hidden="true">↑</span>
              <strong>{file ? file.name : 'Drop your audio here'}</strong>
              <span>{file ? `${(file.size / 1_000_000).toFixed(2)} MB · ready to transcribe` : 'or click to choose a clip'}</span>
              <span className="formats">WAV · AIF · MP3 &nbsp; / &nbsp; &lt; 6 MB</span>
            </label>
            <p className="hint">Use a clip of 30 seconds or less. Longer recordings will be rejected during processing.</p>
            {error && <p role="alert" className="error">{error}</p>}
            <button className="primary" disabled={!file || busy} type="submit">{uploading ? 'Uploading…' : busy ? 'Transcription in progress…' : 'Transcribe bass'}<span aria-hidden="true">↗</span></button>
          </form>
          <div className="privacy"><span aria-hidden="true">◉</span> Audio is processed by your local worker.</div>
        </section>
        <section className="panel result-panel" aria-labelledby="result-heading">
          <div className="panel-heading"><span className="number">02</span><h2 id="result-heading">Your bass line</h2><span className="small-tag">{job?.status || (jobId ? 'CONNECTING' : 'OUTPUT')}</span></div>
          <div className="result-content" aria-live="polite">
            <div className={`waveform ${busy ? 'active' : ''}`} aria-hidden="true">{Array.from({ length: 35 }, (_, index) => <i key={index} style={{ height: `${14 + Math.sin(index * 1.9) ** 2 * 55}px`, animationDelay: `${index * 0.04}s` }} />)}</div>
            <h3>{uploading ? 'Sending your clip' : job ? labels[job.status] : jobId ? 'Reconnecting to your job' : 'A little low-end magic.'}</h3>
            <p>{jobId ? fileName : 'Upload a clip to separate the bass and extract the notes.'}</p>
            {jobId && <ol className="steps">{['Queued', 'Separating & transcribing', 'Ready'].map((label, index) => <li key={label} className={step >= index + 1 ? 'reached' : ''}><span>{step > index + 1 ? '✓' : index + 1}</span>{label}</li>)}</ol>}
            {job?.status === 'QUEUED' && <p className="hint">Waiting for the local worker. Make sure it’s running.</p>}
            {job?.status === 'PROCESSING' && <p className="hint">CPU processing may take a little while. You can refresh this page and return to this job.</p>}
            {job?.status === 'FAILED' && <p role="alert" className="error">{job.errorMessage || 'Processing failed. Try a different short audio clip.'}</p>}
            {pollError && <div role="alert" className="error">{pollError}<button className="text-button" onClick={() => setRetry(value => value + 1)}>Check again</button></div>}
            {job?.status === 'COMPLETED' && <div className="downloads"><audio controls preload="none" src={`/api/jobs/${jobId}/bass`} aria-label="Listen to the isolated bass stem" /><div><a className="primary" href={`/api/jobs/${jobId}/midi`} download>Download MIDI <span>↓</span></a><a className="secondary" href={`/api/jobs/${jobId}/bass`} download>Bass WAV <span>↓</span></a></div><p className="hint">Basic monophonic MIDI · fixed velocity · 120 BPM</p></div>}
            {jobId && <p className="job-id">JOB {jobId}</p>}
            {(isTerminal(job) || pollError) && <button className="text-button" onClick={reset}>{isTerminal(job) ? 'Start a new transcription' : 'Clear this job from the page'}</button>}
          </div>
        </section>
      </div>
      <section className="notes"><div><span>01 / ISOLATE</span><p>Bring the bass out of the mix.</p></div><div><span>02 / TRANSCRIBE</span><p>Capture one note at a time.</p></div><div><span>03 / MAKE IT YOURS</span><p>Import the MIDI. Play with the line.</p></div></section>
    </main><footer><span>MADE FOR THE LOW END.</span><span>Local processing · Experimental transcription</span></footer>
  </div>;
}

if (document.getElementById('root')) createRoot(document.getElementById('root')).render(<App />);
