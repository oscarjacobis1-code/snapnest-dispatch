export function parseRequestText(text) {
  const fields = {};
  for (const line of String(text || '').split(/\r?\n/)) {
    const match = line.match(/^(Name|Pickup|Destination|Passengers|Time):\s*(.+)$/i);
    if (match) fields[match[1].toLowerCase()] = match[2].trim();
  }
  return fields;
}
