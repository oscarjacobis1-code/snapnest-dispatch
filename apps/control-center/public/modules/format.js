export const escapeHtml = (value = '') => String(value).replace(/[&<>"']/g, (c) => ({ '&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;' }[c]));

export function ago(value) {
  if (!value) return '—';
  const then = new Date(value).getTime();
  if (!Number.isFinite(then)) return '—';
  const seconds = Math.max(0, Math.round((Date.now() - then) / 1000));
  if (seconds < 60) return `${seconds}s ago`;
  const minutes = Math.round(seconds / 60);
  if (minutes < 60) return `${minutes}m ago`;
  const hours = Math.round(minutes / 60);
  if (hours < 24) return `${hours}h ago`;
  return `${Math.round(hours / 24)}d ago`;
}

export function dateTime(value) {
  if (!value) return '—';
  const date = new Date(value);
  if (!Number.isFinite(date.getTime())) return '—';
  return new Intl.DateTimeFormat(undefined, { month:'short', day:'numeric', hour:'numeric', minute:'2-digit' }).format(date);
}

export function durationMinutes(start, end) {
  const a = new Date(start || 0).getTime();
  const b = new Date(end || 0).getTime();
  if (!a || !b || !Number.isFinite(a) || !Number.isFinite(b) || b < a) return null;
  return Math.round((b - a) / 60000);
}

export function statusLabel(status = '') {
  return String(status).replaceAll('_', ' ').replace(/\b\w/g, (c) => c.toUpperCase());
}
