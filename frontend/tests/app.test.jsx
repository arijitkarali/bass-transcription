import React from 'react';
import { describe, it, expect, vi } from 'vitest';
import { render, screen, fireEvent, waitFor } from '@testing-library/react';
import { App } from '../src/main';
import { STORAGE_KEY, validateFile } from '../src/api';

const id = '12345678-1234-1234-1234-123456789abc';
const response = (status, extra = {}) => ({ ok: true, json: async () => ({ id, status, ...extra }) });
function selectFile(name = 'riff.WAV') {
  fireEvent.change(document.getElementById('audio-file'), { target: { files: [new File(['audio'], name)] } });
}
describe('file limits', () => {
  it('matches the API boundary and case-insensitive extensions', () => {
    expect(validateFile({ name: 'riff.WAV', size: 5_999_999 })).toBe('');
    expect(validateFile({ name: 'riff.wav', size: 6_000_000 })).toContain('smaller than 6 MB');
    expect(validateFile({ name: 'riff.flac', size: 10 })).toContain('WAV, AIF, or MP3');
    expect(validateFile({ name: 'riff.mp3', size: 0 })).toContain('empty');
  });
});
describe('transcription flow', () => {
  it('uploads multipart audio, polls and only then exposes downloads', async () => {
    const fetch = vi.fn().mockResolvedValueOnce(response('QUEUED')).mockResolvedValueOnce(response('PROCESSING')).mockResolvedValue(response('COMPLETED'));
    vi.stubGlobal('fetch', fetch);
    render(<App />);
    selectFile();
    fireEvent.click(screen.getByRole('button', { name: /Transcribe bass/ }));
    expect(screen.queryByRole('link', { name: /Download MIDI/ })).not.toBeInTheDocument();
    await screen.findByText('Your bass line is ready');
    expect(fetch.mock.calls[0][0]).toBe('/api/jobs');
    expect(fetch.mock.calls[0][1].body.get('file').name).toBe('riff.WAV');
    expect(screen.getByRole('link', { name: /Download MIDI/ })).toHaveAttribute('href', `/api/jobs/${id}/midi`);
    expect(screen.getByRole('link', { name: /Bass WAV/ })).toHaveAttribute('href', `/api/jobs/${id}/bass`);
    expect(JSON.parse(localStorage.getItem(STORAGE_KEY)).id).toBe(id);
    const calls = fetch.mock.calls.length;
    await new Promise(resolve => setTimeout(resolve, 1600));
    expect(fetch).toHaveBeenCalledTimes(calls);
    fireEvent.click(screen.getByRole('button', { name: 'Start a new transcription' }));
    expect(localStorage.getItem(STORAGE_KEY)).toBeNull();
    expect(screen.getByRole('button', { name: /Transcribe bass/ })).toBeDisabled();
  });
  it('restores a job after refresh and displays worker failure details', async () => {
    localStorage.setItem(STORAGE_KEY, JSON.stringify({ id, fileName: 'long.mp3' }));
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(response('FAILED', { errorMessage: 'Audio exceeds 30 seconds' })));
    render(<App />);
    expect(await screen.findByRole('alert')).toHaveTextContent('Audio exceeds 30 seconds');
    expect(screen.queryByRole('link', { name: /Download MIDI/ })).not.toBeInTheDocument();
    expect(screen.getByText('long.mp3')).toBeInTheDocument();
  });
  it('keeps a saved job through a network interruption and supports checking again', async () => {
    localStorage.setItem(STORAGE_KEY, JSON.stringify({ id }));
    const fetch = vi.fn().mockRejectedValueOnce(new TypeError('Failed to fetch')).mockResolvedValue(response('COMPLETED'));
    vi.stubGlobal('fetch', fetch);
    render(<App />);
    expect(await screen.findByRole('alert')).toHaveTextContent('Connection interrupted');
    expect(localStorage.getItem(STORAGE_KEY)).not.toBeNull();
    fireEvent.click(screen.getByRole('button', { name: 'Check again' }));
    await screen.findByRole('link', { name: /Download MIDI/ });
  });
  it('shows rejected upload errors without submitting another request', async () => {
    const fetch = vi.fn().mockResolvedValue({ ok: false, status: 413, json: async () => ({}) });
    vi.stubGlobal('fetch', fetch);
    render(<App />); selectFile();
    fireEvent.click(screen.getByRole('button', { name: /Transcribe bass/ }));
    expect(await screen.findByRole('alert')).toHaveTextContent('smaller than 6 MB');
    expect(fetch).toHaveBeenCalledTimes(1);
    await waitFor(() => expect(screen.getByRole('button', { name: /Transcribe bass/ })).toBeEnabled());
  });
});
