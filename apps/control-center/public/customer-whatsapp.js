export function buildWhatsAppUrl(number, request) {
  if (!/^\d{8,15}$/.test(number)) throw new Error('The taxi base WhatsApp number is not configured.');
  const name = String(request.name || '').trim().slice(0, 80);
  const pickup = String(request.pickup || '').trim().slice(0, 160);
  const destination = String(request.destination || '').trim().slice(0, 160);
  const passengers = Number(request.passengers);
  if (!name || !pickup || !destination || !Number.isInteger(passengers) || passengers < 1 || passengers > 8) {
    throw new Error('Enter your name, pickup, destination and number of passengers.');
  }
  const time = request.later ? 'Please arrange a later pickup time with me.' : 'As soon as possible';
  const message = `Taxi request\nName: ${name}\nPickup: ${pickup}\nDestination: ${destination}\nPassengers: ${passengers}\nTime: ${time}`;
  return `https://wa.me/${number}?text=${encodeURIComponent(message)}`;
}

if (typeof document !== 'undefined') {
  const form = document.querySelector('#customerForm');
  const status = document.querySelector('#customerStatus');
  let number = null;
  fetch('/api/public-config').then(async (response) => {
    if (!response.ok) throw new Error('Could not load the taxi base contact.');
    number = (await response.json()).whatsappNumber;
    if (!number) status.textContent = 'WhatsApp booking is not available yet. Please call the taxi base.';
  }).catch(() => { status.textContent = 'Could not load the taxi base contact. Try again later.'; });

  document.querySelector('#useLocation').addEventListener('click', () => {
    if (!navigator.geolocation) { status.textContent = 'Location is unavailable on this device.'; return; }
    status.textContent = 'Getting your location…';
    navigator.geolocation.getCurrentPosition(({ coords }) => {
      document.querySelector('#pickup').value = `${coords.latitude.toFixed(6)}, ${coords.longitude.toFixed(6)}`;
      status.textContent = 'GPS location added. Add a landmark if it helps the driver find you.';
    }, () => { status.textContent = 'Location was unavailable. Enter a pickup landmark instead.'; }, { timeout: 10000, enableHighAccuracy: true });
  });

  form.addEventListener('submit', (event) => {
    event.preventDefault();
    try {
      const url = buildWhatsAppUrl(number, {
        name: document.querySelector('#customerName').value,
        pickup: document.querySelector('#pickup').value,
        destination: document.querySelector('#destination').value,
        passengers: document.querySelector('#passengers').value,
        later: document.querySelector('#pickupTime').value === 'later'
      });
      window.location.assign(url);
    } catch (error) { status.textContent = error.message; }
  });
}
