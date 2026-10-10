import { api } from '/session.js';

if (typeof document !== 'undefined') {
  const list = document.querySelector('#whatsappInbox');
  let inFlight = false;

  async function refresh() {
    if (!list || inFlight || !document.querySelector('[data-page="bookings"]')?.classList.contains('active')) return;
    inFlight = true;
    try {
      const { messages = [] } = await api('/api/whatsapp/inbox?limit=30');
      list.replaceChildren();
      if (!messages.length) { list.textContent = 'No WhatsApp messages yet.'; return; }
      for (const row of messages) {
        const message = row.payload || {};
        const item = document.createElement('article');
        item.className = 'dispatch-panel';
        item.style.marginBottom = '8px';
        const title = document.createElement('strong');
        title.textContent = `${message.name || 'Customer'} · ${message.sender || 'Unknown'} · ${new Date(row.created_at).toLocaleString()}`;
        const details = document.createElement('p');
        details.className = 'muted';
        details.style.whiteSpace = 'pre-wrap';
        details.style.margin = '8px 0';
        details.textContent = message.type === 'location' ? `Shared location: ${message.location?.label || ''} (${message.location?.lat}, ${message.location?.lng})` : (message.text || 'Message');
        const actions = document.createElement('div');
        actions.className = 'row';
        const draft = document.createElement('button');
        draft.type = 'button'; draft.className = 'btn secondary compact'; draft.textContent = 'Use in booking form';
        draft.addEventListener('click', () => document.dispatchEvent(new CustomEvent('snapnest:whatsapp-draft', { detail: message })));
        actions.appendChild(draft);
        if (/^\d{8,15}$/.test(message.sender || '')) {
          const reply = document.createElement('a');
          reply.className = 'btn secondary compact'; reply.textContent = 'Reply in WhatsApp';
          reply.href = `https://wa.me/${message.sender}`; reply.target = '_blank'; reply.rel = 'noopener noreferrer';
          actions.appendChild(reply);
        }
        item.append(title, details, actions);
        list.appendChild(item);
      }
    } catch (error) { list.textContent = error.message || 'Could not load WhatsApp messages.'; }
    finally { inFlight = false; }
  }

  document.querySelector('#refreshWhatsApp')?.addEventListener('click', refresh);
  document.addEventListener('click', (event) => {
    if (event.target.closest('[data-page-target="bookings"], [data-open-page="bookings"]')) setTimeout(refresh, 0);
  });
  setInterval(refresh, 15000);
}
