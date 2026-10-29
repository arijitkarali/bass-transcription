import React, { useEffect, useRef, useState } from 'react';
import { apiRequest, isTerminal } from './api';

export const jobName = job => job.downloadName || job.userFileName || job.id;
const statuses = { QUEUED: 'Queued', PROCESSING: 'Processing', COMPLETED: 'Completed', FAILED: 'Failed' };
const date = value => value ? new Date(value).toLocaleString([], { dateStyle: 'medium', timeStyle: 'short' }) : '—';
const READ_KEY = 'bass-transcription.read-notifications';
const noticeKey = job => `${job.id}:${job.updatedAt}:${job.status}`;

export function Notifications({ activity, onOpen }) {
  const [open, setOpen] = useState(false);
  const [read, setRead] = useState(() => {
    try { const saved = JSON.parse(localStorage.getItem(READ_KEY)); return Array.isArray(saved) ? saved : []; } catch { return []; }
  });
  const panel = useRef(null);
  const unread = activity.filter(job => !read.includes(noticeKey(job))).length;
  function markRead() {
    const next = [...new Set([...read, ...activity.map(noticeKey)])].slice(-1000);
    setRead(next);
    try { localStorage.setItem(READ_KEY, JSON.stringify(next)); } catch { /* Optional local preference. */ }
  }
  useEffect(() => {
    if (!open) return;
    function close(event) {
      if (event.type === 'keydown' ? event.key === 'Escape' : !panel.current?.contains(event.target)) setOpen(false);
    }
    document.addEventListener('pointerdown', close);
    document.addEventListener('keydown', close);
    return () => { document.removeEventListener('pointerdown', close); document.removeEventListener('keydown', close); };
  }, [open]);
  return <div className="notification-wrap" ref={panel}>
    <button className="notification-button" aria-expanded={open} aria-controls="notifications" onClick={() => { if (!open) markRead(); setOpen(!open); }}>
      <svg aria-hidden="true" width="16" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.6"><path d="M18 8a6 6 0 0 0-12 0c0 7-3 7-3 9h18c0-2-3-2-3-9ZM10 21h4" /></svg> Notifications {unread > 0 && <span className="notification-count" aria-label={`${unread} unread notifications`}>{unread}</span>}
    </button>
    {open && <section id="notifications" className="notification-panel" aria-label="Notifications">
      <div className="notification-heading"><h2>Recent activity</h2><button onClick={markRead}>Mark all read</button></div>
      <p className="hint">Your latest 30 completed or failed submissions.</p>
      {activity.length === 0 ? <p className="empty-note">No notifications yet. Upload a clip to get started.</p> : <ul>{activity.map(job => <li key={noticeKey(job)}>
        <span className={`activity-dot ${job.status.toLowerCase()}`} />
        <div><button className="notification-name" onClick={() => { onOpen(job); setOpen(false); markRead(); }}>{jobName(job)}</button><p>{job.status === 'COMPLETED' ? 'Your bass line is ready to download.' : job.errorMessage || 'Transcription failed.'}</p><time>{date(job.updatedAt)}</time></div>
      </li>)}</ul>}
    </section>}
  </div>;
}

export function Dashboard({ refreshToken, onOpen, onDeleted, onActivity }) {
  const [page, setPage] = useState(0);
  const [pageSize, setPageSize] = useState(10);
  const [search, setSearch] = useState('');
  const [query, setQuery] = useState('');
  const [status, setStatus] = useState('');
  const [data, setData] = useState(null);
  const [error, setError] = useState('');
  const [actionError, setActionError] = useState('');
  const [notice, setNotice] = useState('');
  const [selected, setSelected] = useState(new Set());
  const [format, setFormat] = useState('both');
  const [working, setWorking] = useState(false);
  const [confirmDelete, setConfirmDelete] = useState(false);
  const [reload, setReload] = useState(0);
  const selectAll = useRef(null);
  const confirmation = useRef(null);
  const items = data?.items || [];
  const chosen = items.filter(job => selected.has(job.id));
  const allCompleted = chosen.length > 0 && chosen.every(job => job.status === 'COMPLETED');
  const allTerminal = chosen.length > 0 && chosen.every(isTerminal);
  const allSelected = items.length > 0 && chosen.length === items.length;
  const totalPages = Math.max(1, Math.ceil((data?.total || 0) / pageSize));

  useEffect(() => {
    if (search === query) return;
    const timer = setTimeout(() => { setQuery(search); setPage(0); setSelected(new Set()); }, 250);
    return () => clearTimeout(timer);
  }, [search, query]);
  useEffect(() => {
    if (confirmDelete) { confirmation.current?.focus(); confirmation.current?.scrollIntoView?.({ behavior: 'smooth', block: 'center' }); }
  }, [confirmDelete]);
  useEffect(() => { if (selectAll.current) selectAll.current.indeterminate = chosen.length > 0 && !allSelected; }, [chosen.length, allSelected]);
  useEffect(() => {
    const controller = new AbortController();
    let timer;
    async function load() {
      try {
        const params = new URLSearchParams({ page, size: pageSize, search: query });
        if (status) params.set('status', status);
        const next = await apiRequest(`/api/jobs?${params}`, { signal: AbortSignal.any([controller.signal, AbortSignal.timeout(15_000)]) });
        if (!Array.isArray(next.items) || !Number.isFinite(next.total) || !Array.isArray(next.activity)) throw new Error('The API returned an unexpected submissions response.');
        if (controller.signal.aborted) return;
        if (page > 0 && page >= Math.max(1, Math.ceil(next.total / pageSize))) { setPage(Math.max(0, Math.ceil(next.total / pageSize) - 1)); return; }
        setData(next); setError(''); onActivity(next.activity);
        setSelected(previous => new Set([...previous].filter(id => next.items.some(job => job.id === id))));
      } catch (err) {
        if (!controller.signal.aborted) setError(`Could not refresh submissions. ${err.message}`);
      } finally {
        if (!controller.signal.aborted) timer = setTimeout(load, 3000);
      }
    }
    setData(null); setConfirmDelete(false);
    load();
    return () => { controller.abort(); clearTimeout(timer); };
  }, [page, pageSize, query, status, refreshToken, reload, onActivity]);

  function toggle(id) { setSelected(previous => { const next = new Set(previous); if (next.has(id)) next.delete(id); else next.add(id); return next; }); setConfirmDelete(false); }
  async function download() {
    setWorking(true); setActionError(''); setNotice('');
    try {
      const response = await apiRequest(`/api/jobs/downloads?format=${format}`, {
        method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ ids: chosen.map(job => job.id) }),
        signal: AbortSignal.timeout(120_000), raw: true,
      });
      const blob = await response.blob();
      const url = URL.createObjectURL(blob);
      const link = document.createElement('a'); link.href = url; link.download = 'bass-results.zip'; document.body.append(link); link.click(); link.remove();
      setTimeout(() => URL.revokeObjectURL(url), 60_000);
      setNotice(`ZIP ready with ${chosen.length} submission${chosen.length === 1 ? '' : 's'}.`);
    } catch (err) { setActionError(`Download failed. ${err.message}`); }
    finally { setWorking(false); }
  }
  async function remove() {
    setWorking(true); setActionError(''); setNotice('');
    try {
      const result = await apiRequest('/api/jobs/delete', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ ids: chosen.map(job => job.id) }) });
      onDeleted(result.ids); setSelected(new Set()); setConfirmDelete(false); setReload(value => value + 1);
      setNotice(`Deleted ${result.ids.length} submission${result.ids.length === 1 ? '' : 's'}.`);
      if (result.cleanupWarnings?.length) setActionError(result.cleanupWarnings.join(' '));
    } catch (err) { setActionError(`Delete failed. ${err.message}`); setReload(value => value + 1); }
    finally { setWorking(false); }
  }
  return <section className="dashboard" aria-labelledby="dashboard-heading">
    <div className="dashboard-title"><div><div className="eyebrow">YOUR LOCAL LIBRARY</div><h2 id="dashboard-heading">Submissions</h2><p>Every upload, from the first note to the final download.</p></div><span className="live-indicator"><i /> Updates every 3 seconds</span></div>
    <div className="stats-grid">{Object.entries(statuses).map(([key, label]) => <button key={key} className={`stat-card ${status === key ? 'selected' : ''}`} aria-pressed={status === key} disabled={working} onClick={() => { setStatus(status === key ? '' : key); setPage(0); setSelected(new Set()); }}><span>{label}</span><strong>{data?.stats?.[key] ?? '—'}</strong><span className={`status-marker ${key.toLowerCase()}`} /></button>)}</div>
    <div className="library-panel">
      <div className="library-tools"><label className="search-field"><span aria-hidden="true">⌕</span><input aria-label="Search submissions" disabled={working} maxLength={255} value={search} placeholder="Search filenames…" onChange={event => setSearch(event.target.value)} /></label><select aria-label="Filter by status" disabled={working} value={status} onChange={event => { setStatus(event.target.value); setPage(0); setSelected(new Set()); }}><option value="">All statuses</option>{Object.entries(statuses).map(([key, label]) => <option key={key} value={key}>{label}</option>)}</select><button className="plain-button" onClick={() => setReload(value => value + 1)}>Refresh</button></div>
      <div className="bulk-tools"><span>{chosen.length ? `${chosen.length} selected` : 'Select submissions for bulk actions'}</span><div><select aria-label="Download format" value={format} onChange={event => setFormat(event.target.value)} disabled={working}><option value="both">MIDI + WAV</option><option value="midi">MIDI only</option><option value="bass">WAV only</option></select><button className="plain-button" disabled={!allCompleted || chosen.length > 20 || working} onClick={download}>{working ? 'Working…' : 'Download ZIP'}</button><button className="danger-button" disabled={!allTerminal || chosen.length > 20 || working} onClick={() => { setConfirmDelete(true); setActionError(''); }}>Delete selected</button></div></div>
      <p className="bulk-hint">Download completed jobs. Delete completed or failed jobs. {chosen.length > 20 ? 'Choose 20 or fewer submissions to use bulk actions.' : 'Up to 20 at a time.'}</p>
      {confirmDelete && <div className="delete-confirm" ref={confirmation} tabIndex={-1} role="alertdialog" aria-labelledby="delete-heading"><div><h3 id="delete-heading">Delete {chosen.length} submission{chosen.length === 1 ? '' : 's'}?</h3><p>This permanently removes their history, uploaded audio and generated results.</p></div><button className="plain-button" disabled={working} onClick={() => setConfirmDelete(false)}>Cancel</button><button className="danger-button" disabled={working || !allTerminal} onClick={remove}>Confirm delete</button></div>}
      {error && <div role="alert" className="error dashboard-message">{error} <button className="text-button" onClick={() => setReload(value => value + 1)}>Try again</button></div>}
      {actionError && <p role="alert" className="error dashboard-message">{actionError}</p>}
      {notice && <p role="status" className="success-message">{notice}</p>}
      {!data && !error && <p className="empty-note">Loading your submissions…</p>}
      {data && items.length === 0 ? <div className="empty-library"><span aria-hidden="true">≋</span><h3>{query || status ? 'No matching submissions' : 'Your library starts here'}</h3><p>{query || status ? 'Try another filename or status.' : 'Upload your first clip above. Its progress and results will appear here.'}</p></div> : items.length > 0 && <div className="table-scroll"><table><thead><tr><th className="checkbox-cell"><input ref={selectAll} type="checkbox" aria-label="Select all submissions on this page" checked={allSelected} disabled={working} onChange={() => { setSelected(allSelected ? new Set() : new Set(items.map(job => job.id))); setConfirmDelete(false); }} /></th><th>Recording</th><th>Submitted</th><th>Status</th><th>Actions</th></tr></thead><tbody>{items.map(job => <tr key={job.id} className={selected.has(job.id) ? 'row-selected' : ''}>
        <td className="checkbox-cell"><input type="checkbox" aria-label={`Select ${jobName(job)}`} checked={selected.has(job.id)} disabled={working} onChange={() => toggle(job.id)} /></td>
        <td><button className="recording-name" onClick={() => onOpen(job)}>{jobName(job)}</button><span className="row-detail">{job.userFileName && job.downloadName !== job.userFileName ? `Original: ${job.userFileName}` : `ID ${job.id.slice(0, 8)}`}</span>{job.status === 'FAILED' && <span className="row-failure">{job.errorMessage || 'Processing failed'}</span>}</td>
        <td className="date-cell">{date(job.createdAt)}</td><td><span className={`status-pill ${job.status.toLowerCase()}`}><i />{statuses[job.status]}</span></td>
        <td><div className="row-actions"><button className="plain-button" onClick={() => onOpen(job)}>Open</button>{job.status === 'COMPLETED' && <><a href={`/api/jobs/${job.id}/midi`} download aria-label={`Download MIDI for ${jobName(job)}`}>MIDI ↓</a><a href={`/api/jobs/${job.id}/bass`} download aria-label={`Download WAV for ${jobName(job)}`}>WAV ↓</a></>}{isTerminal(job) && <button className="row-delete" disabled={working} aria-label={`Delete ${jobName(job)}`} onClick={() => { setSelected(new Set([job.id])); setConfirmDelete(true); setActionError(''); }}>Delete</button>}</div></td>
      </tr>)}</tbody></table></div>}
      {data && <div className="pagination"><span>{data.total === 0 ? '0 submissions' : `${page * pageSize + 1}–${page * pageSize + items.length} of ${data.total} submissions`}</span><label className="page-size">Rows per page <select aria-label="Rows per page" value={pageSize} disabled={working} onChange={event => { setPageSize(Number(event.target.value)); setPage(0); setSelected(new Set()); }}><option value="10">10</option><option value="20">20</option><option value="50">50</option></select></label><div><button className="plain-button" disabled={page === 0 || working} onClick={() => { setPage(page - 1); setSelected(new Set()); setConfirmDelete(false); }}>Previous</button><span>Page {page + 1} of {totalPages}</span><button className="plain-button" disabled={page + 1 >= totalPages || working} onClick={() => { setPage(page + 1); setSelected(new Set()); setConfirmDelete(false); }}>Next</button></div></div>}
    </div>
  </section>;
}
