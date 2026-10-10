import { api } from '/session.js';
import '/pwa.js';
import '/safety-watch.js';
import '/reservations-ui.js';
import '/operator-shift-ui.js';
import '/live-trip-ui.js';

let room = null;
let leaseToken = null;
let heartbeatTimer = null;
let connected = false;
let enabled = false;

const button = document.querySelector('#pttButton');
const status = document.querySelector('#pttStatus');

function setStatus(text, state = '') {
  if (status) status.textContent = text;
  if (button) {
    button.dataset.pttState = state;
    button.disabled = state === 'offline' || state === 'connecting';
  }
}

async function connectPtt() {
  if (!button || !window.LivekitClient) return;
  setStatus('Connecting radio…', 'connecting');
  try {
    const details = await api('/api/ptt/token');
    enabled = Boolean(details.enabled);
    if (!enabled) {
      setStatus('PTT not configured', 'offline');
      return;
    }

    room = new LivekitClient.Room({ adaptiveStream: true, dynacast: true });
    room.on(LivekitClient.RoomEvent.TrackSubscribed, (track) => {
      if (track.kind !== LivekitClient.Track.Kind.Audio) return;
      const audio = track.attach();
      audio.autoplay = true;
      audio.dataset.snapnestPtt = 'true';
      document.body.appendChild(audio);
    });
    room.on(LivekitClient.RoomEvent.TrackUnsubscribed, (track) => track.detach().forEach((el) => el.remove()));
    room.on(LivekitClient.RoomEvent.Disconnected, () => {
      connected = false;
      setStatus('PTT disconnected', 'offline');
    });
    await room.connect(details.serverUrl, details.participantToken);
    await room.localParticipant.setMicrophoneEnabled(false);
    connected = true;
    setStatus('Radio ready', 'ready');
  } catch (error) {
    console.error('PTT connect failed', error);
    setStatus('PTT offline', 'offline');
  }
}

async function beginTalk(event) {
  event?.preventDefault();
  if (!enabled || !connected || !room) return;
  try {
    await room.startAudio?.();
    setStatus('Requesting floor…', 'connecting');
    const lease = await api('/api/ptt/floor/acquire', { method: 'POST', body: '{}' });
    if (!lease.granted || !lease.leaseToken) {
      setStatus('Radio busy', 'busy');
      return;
    }
    leaseToken = lease.leaseToken;
    await room.localParticipant.setMicrophoneEnabled(true);
    setStatus('TALKING', 'talking');
    heartbeatTimer = setInterval(async () => {
      if (!leaseToken) return;
      try {
        const beat = await api('/api/ptt/floor/heartbeat', { method: 'POST', body: JSON.stringify({ leaseToken }) });
        if (!beat.granted) await endTalk();
      } catch {
        await endTalk();
      }
    }, 5000);
  } catch (error) {
    console.error('PTT start failed', error);
    await endTalk();
    setStatus('PTT error', 'offline');
  }
}

async function endTalk(event) {
  event?.preventDefault();
  if (heartbeatTimer) clearInterval(heartbeatTimer);
  heartbeatTimer = null;
  try { await room?.localParticipant.setMicrophoneEnabled(false); } catch {}
  const token = leaseToken;
  leaseToken = null;
  if (token) {
    try { await api('/api/ptt/floor/release', { method: 'POST', body: JSON.stringify({ leaseToken: token }) }); } catch {}
  }
  if (connected) setStatus('Radio ready', 'ready');
}

if (button) {
  button.addEventListener('pointerdown', beginTalk);
  button.addEventListener('pointerup', endTalk);
  button.addEventListener('pointercancel', endTalk);
  button.addEventListener('pointerleave', (event) => { if (event.buttons) endTalk(event); });
  button.addEventListener('contextmenu', (event) => event.preventDefault());
  connectPtt();
}

window.addEventListener('beforeunload', () => {
  if (heartbeatTimer) clearInterval(heartbeatTimer);
  room?.disconnect();
});
