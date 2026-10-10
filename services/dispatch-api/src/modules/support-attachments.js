import { randomUUID } from 'node:crypto';
import { runtimeConfig } from '../config.js';

export const SUPPORT_BUCKET = 'dispatch-support';
export const SUPPORT_ATTACHMENT_MAX_BYTES = 3 * 1024 * 1024;
const ALLOWED_TYPES = new Map([
  ['image/jpeg', 'jpg'],
  ['image/png', 'png'],
  ['image/webp', 'webp']
]);

function httpError(status, message) {
  const error = new Error(message);
  error.status = status;
  return error;
}

function storageConfig() {
  const config = runtimeConfig();
  if (!config.persistent || !config.supabaseUrl || !config.supabaseSecretKey) {
    throw httpError(503, 'Support attachment storage is unavailable.');
  }
  return config;
}

function encodedPath(path) {
  return String(path).split('/').filter(Boolean).map(encodeURIComponent).join('/');
}

function parseRef(ref) {
  const prefix = `storage://${SUPPORT_BUCKET}/`;
  const value = String(ref || '');
  if (!value.startsWith(prefix)) throw httpError(400, 'Invalid support attachment reference.');
  const objectPath = value.slice(prefix.length);
  if (!objectPath || objectPath.includes('..')) throw httpError(400, 'Invalid support attachment reference.');
  return objectPath;
}

export function isAllowedSupportAttachmentRef(ref, tenantId, ownerId = null) {
  if (!ref) return true;
  try {
    const objectPath = parseRef(ref);
    const prefix = ownerId ? `${tenantId}/${ownerId}/` : `${tenantId}/`;
    return objectPath.startsWith(prefix);
  } catch {
    return false;
  }
}

export async function readSupportImage(req) {
  const contentType = String(req.headers['content-type'] || '').split(';')[0].trim().toLowerCase();
  const extension = ALLOWED_TYPES.get(contentType);
  if (!extension) throw httpError(415, 'Attach a JPEG, PNG or WebP image.');

  const declared = Number(req.headers['content-length'] || 0);
  if (declared > SUPPORT_ATTACHMENT_MAX_BYTES) throw httpError(413, 'Screenshot is too large. Maximum size is 3 MB.');

  const chunks = [];
  let size = 0;
  for await (const chunk of req) {
    size += chunk.length;
    if (size > SUPPORT_ATTACHMENT_MAX_BYTES) throw httpError(413, 'Screenshot is too large. Maximum size is 3 MB.');
    chunks.push(chunk);
  }
  if (!size) throw httpError(400, 'Screenshot is empty.');
  return { bytes: Buffer.concat(chunks), contentType, extension };
}

export async function uploadSupportAttachment({ tenantId, ownerId, bytes, contentType, extension }) {
  const config = storageConfig();
  const safeOwner = String(ownerId || 'operator').replace(/[^a-zA-Z0-9_-]/g, '_');
  const objectPath = `${tenantId}/${safeOwner}/${Date.now()}-${randomUUID()}.${extension}`;
  const response = await fetch(`${config.supabaseUrl}/storage/v1/object/${SUPPORT_BUCKET}/${encodedPath(objectPath)}`, {
    method: 'POST',
    headers: {
      apikey: config.supabaseSecretKey,
      authorization: `Bearer ${config.supabaseSecretKey}`,
      'content-type': contentType,
      'x-upsert': 'false'
    },
    body: bytes
  });
  if (!response.ok) {
    const text = await response.text();
    throw httpError(502, `Screenshot upload failed${text ? `: ${text.slice(0, 160)}` : '.'}`);
  }
  return `storage://${SUPPORT_BUCKET}/${objectPath}`;
}

export async function signSupportAttachment(ref, expiresIn = 300) {
  const config = storageConfig();
  const objectPath = parseRef(ref);
  const response = await fetch(`${config.supabaseUrl}/storage/v1/object/sign/${SUPPORT_BUCKET}/${encodedPath(objectPath)}`, {
    method: 'POST',
    headers: {
      apikey: config.supabaseSecretKey,
      authorization: `Bearer ${config.supabaseSecretKey}`,
      'content-type': 'application/json'
    },
    body: JSON.stringify({ expiresIn })
  });
  const data = await response.json().catch(() => ({}));
  if (!response.ok) throw httpError(502, data?.message || data?.error || 'Could not open screenshot.');
  const signed = data.signedURL || data.signedUrl;
  if (!signed) throw httpError(502, 'Storage did not return a signed screenshot URL.');
  if (/^https?:\/\//i.test(signed)) return signed;
  const path = signed.startsWith('/storage/v1/') ? signed : `/storage/v1${signed.startsWith('/') ? signed : `/${signed}`}`;
  return `${config.supabaseUrl}${path}`;
}
