import { runtimeConfig } from '../config.js';
import { SupabaseRest } from '../supabase-rest.js';

function httpError(status, message) {
  const error = new Error(message);
  error.status = status;
  return error;
}

function database() {
  const config = runtimeConfig();
  if (!config.persistent) throw httpError(503, 'Operator shift tracking is unavailable in this environment.');
  return new SupabaseRest({
    url: config.supabaseUrl,
    secretKey: config.supabaseSecretKey,
    publishableKey: config.supabasePublishableKey
  });
}

const first = (value) => Array.isArray(value) ? value[0] : value;

export async function startOperatorShift({ tenantId, userId, role, email }) {
  const db = database();
  const current = first(await db.request('operator_shifts', {
    query: {
      tenant_id: `eq.${tenantId}`,
      user_id: `eq.${userId}`,
      ended_at: 'is.null',
      select: '*',
      limit: 1
    }
  }));
  if (current) {
    const rows = await db.request('operator_shifts', {
      method: 'PATCH',
      query: { id: `eq.${current.id}`, tenant_id: `eq.${tenantId}` },
      body: {
        last_seen_at: new Date().toISOString(),
        operator_email: String(email || current.operator_email || '').trim() || null
      },
      prefer: 'return=representation'
    });
    return first(rows) || current;
  }
  const rows = await db.request('operator_shifts', {
    method: 'POST',
    body: {
      tenant_id: tenantId,
      user_id: userId,
      role,
      operator_email: String(email || '').trim() || null,
      started_at: new Date().toISOString(),
      last_seen_at: new Date().toISOString()
    },
    prefer: 'return=representation'
  });
  return first(rows);
}

export async function operatorShiftStatus({ tenantId, userId, handoverLimit = 8 }) {
  const db = database();
  const [currentRows, handovers] = await Promise.all([
    db.request('operator_shifts', {
      query: {
        tenant_id: `eq.${tenantId}`,
        user_id: `eq.${userId}`,
        ended_at: 'is.null',
        select: '*',
        limit: 1
      }
    }),
    db.request('operator_shifts', {
      query: {
        tenant_id: `eq.${tenantId}`,
        ended_at: 'not.is.null',
        handover_note: 'not.is.null',
        select: 'id,user_id,operator_email,role,started_at,ended_at,handover_note,handover_snapshot',
        order: 'ended_at.desc',
        limit: Math.max(1, Math.min(20, Number(handoverLimit) || 8))
      }
    })
  ]);
  return { current: first(currentRows) || null, handovers };
}

export async function endOperatorShift({ tenantId, userId, note, snapshot }) {
  const cleanNote = String(note || '').trim().slice(0, 2000);
  if (!cleanNote) throw httpError(400, 'Add a handover note before ending the shift.');
  const db = database();
  const current = first(await db.request('operator_shifts', {
    query: {
      tenant_id: `eq.${tenantId}`,
      user_id: `eq.${userId}`,
      ended_at: 'is.null',
      select: 'id',
      limit: 1
    }
  }));
  if (!current) throw httpError(409, 'No active dispatcher shift was found.');
  const rows = await db.request('operator_shifts', {
    method: 'PATCH',
    query: { id: `eq.${current.id}`, tenant_id: `eq.${tenantId}` },
    body: {
      ended_at: new Date().toISOString(),
      last_seen_at: new Date().toISOString(),
      handover_note: cleanNote,
      handover_snapshot: snapshot || {}
    },
    prefer: 'return=representation'
  });
  return first(rows);
}
