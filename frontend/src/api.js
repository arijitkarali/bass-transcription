export const MAX_BYTES = 20_000_000;
export const STORAGE_KEY = 'bass-transcription.current-job';
export const isTerminal = (job) => ['COMPLETED', 'FAILED'].includes(job?.status);

export function validateFile(file) {
  if (!file) return 'Choose an audio clip first.';
  if (!/\.(wav|aif|mp3)$/i.test(file.name)) return 'Choose a WAV, AIF, or MP3 file.';
  if (file.name.slice(0, file.name.lastIndexOf('.')).length > 255) return 'Filename without its extension must be at most 255 characters.';
  if (!file.size) return 'This file is empty. Choose another clip.';
  if (file.size >= MAX_BYTES) return 'Your clip must be smaller than 20 MB.';
  return '';
}

export async function apiRequest(url, options = {}) {
  const { raw, ...fetchOptions } = options;
  const response = await fetch(url, { ...fetchOptions, signal: options.signal || AbortSignal.timeout(20_000) });
  if (!response.ok) {
    const fallback = {
      400: 'The upload could not be read. Choose a valid audio clip.',
      404: 'This job was not found. Start a new transcription.',
      413: 'Your clip must be smaller than 20 MB.',
      415: 'Only WAV, AIF, and MP3 files are accepted.',
    }[response.status] || 'The local API is unavailable. Check that the backend is running.';
    const body = await response.json().catch(() => null);
    const error = new Error(body?.detail || body?.message || fallback);
    error.status = response.status;
    throw error;
  }
  return raw ? response : response.json();
}

export async function requestJob(url, options = {}) {
  const job = await apiRequest(url, options);
  if (!/^[a-f0-9-]{36}$/i.test(job?.id) || !['QUEUED', 'PROCESSING', 'COMPLETED', 'FAILED'].includes(job?.status)) {
    throw new Error('The API returned an unexpected job response.');
  }
  return job;
}

export function readSavedJob() {
  try {
    const saved = JSON.parse(localStorage.getItem(STORAGE_KEY));
    return /^[a-f0-9-]{36}$/i.test(saved?.id) ? saved : null;
  } catch { return null; }
}
export function saveJob(value) {
  try {
    if (value) localStorage.setItem(STORAGE_KEY, JSON.stringify(value));
    else localStorage.removeItem(STORAGE_KEY);
  } catch { /* Storage may be disabled; the current session still works. */ }
}
