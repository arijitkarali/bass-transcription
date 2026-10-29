import React from 'react';
import { describe, it, expect, vi } from 'vitest';
import { render, screen, fireEvent, waitFor, within } from '@testing-library/react';
import { App } from '../src/main';

const ids = ['12345678-1234-1234-1234-123456789abc', '12345678-1234-1234-1234-123456789abd', '12345678-1234-1234-1234-123456789abe'];
const jobs = [
  { id: ids[0], status: 'COMPLETED', userFileName: 'Song', downloadName: 'Song', createdAt: '2026-10-29T12:00:00Z', updatedAt: '2026-10-29T12:01:00Z' },
  { id: ids[1], status: 'COMPLETED', userFileName: 'Song', downloadName: 'Song (2)', createdAt: '2026-10-29T12:02:00Z', updatedAt: '2026-10-29T12:03:00Z' },
  { id: ids[2], status: 'FAILED', userFileName: null, errorMessage: 'Audio could not be decoded', createdAt: '2026-10-29T12:04:00Z', updatedAt: '2026-10-29T12:05:00Z' },
];
const json = value => ({ ok: true, json: async () => value });
function install(handler = () => null) {
  const fetch = vi.fn(async (url, options) => {
    const custom = await handler(url, options);
    if (custom) return custom;
    if (String(url).startsWith('/api/jobs?')) return json({ items: jobs, total: 3, stats: { COMPLETED: 2, FAILED: 1, QUEUED: 0, PROCESSING: 0 }, activity: jobs });
    return json(jobs[0]);
  });
  vi.stubGlobal('fetch', fetch); return fetch;
}

describe('submissions dashboard', () => {
  it('loads persisted history, filters and opens a failed submission', async () => {
    const fetch = install(); render(<App />);
    await screen.findByRole('button', { name: 'Song (2)' });
    expect(screen.getByRole('checkbox', { name: `Select ${ids[2]}` })).toBeInTheDocument();
    fireEvent.change(screen.getByRole('combobox', { name: 'Filter by status' }), { target: { value: 'FAILED' } });
    await waitFor(() => expect(fetch.mock.calls.some(([url]) => url.includes('status=FAILED'))).toBe(true));
    fireEvent.change(screen.getByRole('textbox', { name: 'Search submissions' }), { target: { value: 'riff' } });
    await waitFor(() => expect(fetch.mock.calls.some(([url]) => url.includes('search=riff'))).toBe(true));
    const row = screen.getByRole('button', { name: ids[2] }).closest('tr');
    fireEvent.click(within(row).getByRole('button', { name: 'Open' }));
    expect(await screen.findByRole('alert')).toHaveTextContent('Audio could not be decoded');
  });

  it('downloads completed selections in one ZIP with the selected format', async () => {
    const fetch = install(url => url.startsWith('/api/jobs/downloads') ? { ok: true, blob: async () => new Blob(['zip']) } : null);
    URL.createObjectURL = vi.fn(() => 'blob:test'); URL.revokeObjectURL = vi.fn();
    const click = vi.spyOn(HTMLAnchorElement.prototype, 'click').mockImplementation(() => {});
    render(<App />); await screen.findByRole('button', { name: 'Song (2)' });
    fireEvent.click(screen.getByRole('checkbox', { name: 'Select Song' }));
    fireEvent.click(screen.getByRole('checkbox', { name: 'Select Song (2)' }));
    fireEvent.change(screen.getByRole('combobox', { name: 'Download format' }), { target: { value: 'midi' } });
    fireEvent.click(screen.getByRole('button', { name: 'Download ZIP' }));
    await screen.findByText('ZIP ready with 2 submissions.');
    const call = fetch.mock.calls.find(([url]) => url.includes('/downloads?'));
    expect(call[0]).toBe('/api/jobs/downloads?format=midi');
    expect(JSON.parse(call[1].body).ids).toEqual(ids.slice(0, 2)); expect(click).toHaveBeenCalled();
    click.mockRestore();
  });

  it('requires confirmation before deleting and protects active uploads', async () => {
    let removed = false;
    const fetch = install(url => {
      if (url === '/api/jobs/delete') { removed = true; return json({ ids: [ids[0]], cleanupWarnings: [] }); }
      if (url.startsWith('/api/jobs?') && removed) return json({ items: [], total: 0, stats: {}, activity: [] });
    });
    render(<App />); await screen.findByRole('button', { name: 'Song (2)' });
    fireEvent.click(screen.getByRole('checkbox', { name: 'Select Song' }));
    fireEvent.click(screen.getByRole('button', { name: 'Delete selected' }));
    expect(fetch.mock.calls.some(([url]) => url === '/api/jobs/delete')).toBe(false);
    expect(screen.getByRole('alertdialog')).toHaveTextContent('permanently removes');
    fireEvent.click(screen.getByRole('button', { name: 'Confirm delete' }));
    await screen.findByText('Deleted 1 submission.');
    await screen.findByText('Your library starts here');
  });

  it('shows recent notifications and persists their read state', async () => {
    install(); const view = render(<App />);
    await screen.findByLabelText('3 unread notifications');
    fireEvent.click(screen.getByRole('button', { name: /Notifications/ }));
    expect(screen.getByRole('region', { name: 'Notifications' })).toHaveTextContent('Audio could not be decoded');
    expect(screen.queryByLabelText('3 unread notifications')).not.toBeInTheDocument();
    view.unmount(); render(<App />); await screen.findByRole('button', { name: 'Song (2)' });
    expect(screen.queryByLabelText('3 unread notifications')).not.toBeInTheDocument();
  });

  it('paginates and disables destructive actions for processing jobs', async () => {
    const fetch = install(url => url.startsWith('/api/jobs?') ? json({ items: [{ ...jobs[0], status: 'PROCESSING' }], total: 11, stats: {}, activity: [] }) : null);
    render(<App />); await screen.findByRole('button', { name: 'Song' });
    fireEvent.click(screen.getByRole('checkbox', { name: 'Select Song' }));
    expect(screen.getByRole('button', { name: 'Download ZIP' })).toBeDisabled();
    expect(screen.getByRole('button', { name: 'Delete selected' })).toBeDisabled();
    fireEvent.click(screen.getByRole('button', { name: 'Next' }));
    await waitFor(() => expect(fetch.mock.calls.some(([url]) => url.includes('page=1'))).toBe(true));
    fireEvent.change(screen.getByRole('combobox', { name: 'Rows per page' }), { target: { value: '20' } });
    await waitFor(() => expect(fetch.mock.calls.some(([url]) => url.includes('size=20') && url.includes('page=0'))).toBe(true));
  });

  it('shows a recoverable dashboard error', async () => {
    let fail = true;
    install(url => url.startsWith('/api/jobs?') && fail ? Promise.reject(new TypeError('Failed to fetch')) : null);
    render(<App />); expect(await screen.findByRole('alert')).toHaveTextContent('Could not refresh submissions');
    fail = false; fireEvent.click(screen.getByRole('button', { name: 'Try again' }));
    await screen.findByRole('button', { name: 'Song (2)' });
    expect(screen.queryByRole('alert')).not.toBeInTheDocument();
  });
});
