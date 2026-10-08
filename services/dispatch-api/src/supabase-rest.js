function addQuery(url, query = {}) {
  for (const [key, value] of Object.entries(query)) {
    if (value === undefined || value === null) continue;
    if (Array.isArray(value)) value.forEach((v) => url.searchParams.append(key, String(v)));
    else url.searchParams.set(key, String(value));
  }
  return url;
}

export class SupabaseRest {
  constructor({ url, secretKey, publishableKey, fetchImpl = fetch }) {
    this.url = url.replace(/\/$/, '');
    this.secretKey = secretKey;
    this.publishableKey = publishableKey;
    this.fetch = fetchImpl;
  }

  async request(resource, { method = 'GET', query, body, prefer, headers = {} } = {}) {
    const url = addQuery(new URL(`${this.url}/rest/v1/${resource}`), query);
    const requestHeaders = {
      apikey: this.secretKey,
      authorization: `Bearer ${this.secretKey}`,
      ...headers
    };
    if (body !== undefined) requestHeaders['content-type'] = 'application/json';
    if (prefer) requestHeaders.prefer = prefer;
    const response = await this.fetch(url, {
      method,
      headers: requestHeaders,
      body: body === undefined ? undefined : JSON.stringify(body)
    });
    const text = await response.text();
    let data = null;
    if (text) {
      try { data = JSON.parse(text); }
      catch { data = text; }
    }
    if (!response.ok) {
      const message = data?.message || data?.error || `${response.status} ${response.statusText}`;
      throw new Error(`Supabase request failed: ${message}`);
    }
    return data;
  }

  rpc(name, args) {
    return this.request(`rpc/${name}`, {
      method: 'POST',
      body: args,
      prefer: 'return=representation'
    });
  }

  async login(email, password) {
    const response = await this.fetch(`${this.url}/auth/v1/token?grant_type=password`, {
      method: 'POST',
      headers: { apikey: this.publishableKey, 'content-type': 'application/json' },
      body: JSON.stringify({ email, password })
    });
    const data = await response.json();
    if (!response.ok) throw new Error(data?.msg || data?.error_description || data?.error || 'Sign-in failed.');
    return data;
  }

  async refresh(refreshToken) {
    const response = await this.fetch(`${this.url}/auth/v1/token?grant_type=refresh_token`, {
      method: 'POST',
      headers: { apikey: this.publishableKey, 'content-type': 'application/json' },
      body: JSON.stringify({ refresh_token: refreshToken })
    });
    const data = await response.json();
    if (!response.ok) throw new Error(data?.msg || data?.error_description || data?.error || 'Session refresh failed.');
    return data;
  }

  async getUser(accessToken) {
    const response = await this.fetch(`${this.url}/auth/v1/user`, {
      headers: { apikey: this.publishableKey, authorization: `Bearer ${accessToken}` }
    });
    const data = await response.json();
    if (!response.ok) throw new Error('Invalid or expired session.');
    return data;
  }
}

export { addQuery };
