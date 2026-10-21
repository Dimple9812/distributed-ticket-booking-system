// Thin fetch wrapper around the REST API. Same origin in production; proxied by Vite in development.

const TOKEN_KEY = 'tb.auth';

export function loadAuth() {
  try {
    const raw = localStorage.getItem(TOKEN_KEY);
    if (!raw) return null;
    const auth = JSON.parse(raw);
    return new Date(auth.expiresAt) > new Date() ? auth : null;
  } catch {
    return null;
  }
}

export function saveAuth(auth) {
  try {
    if (auth) localStorage.setItem(TOKEN_KEY, JSON.stringify(auth));
    else localStorage.removeItem(TOKEN_KEY);
  } catch {
    /* storage unavailable: session lives in memory only */
  }
}

export class ApiError extends Error {
  constructor(status, body) {
    super(body?.message || `Request failed (${status})`);
    this.status = status;
    this.code = body?.code;
    this.details = body?.details;
    this.fieldErrors = body?.fieldErrors || [];
  }
}

export function newIdempotencyKey() {
  return (crypto.randomUUID ? crypto.randomUUID() : `${Date.now()}-${Math.random()}`).replace(/[^A-Za-z0-9-]/g, '');
}

let currentToken = loadAuth()?.accessToken || null;
export function setToken(token) {
  currentToken = token;
}

export async function request(method, path, { body, idempotencyKey, headers = {} } = {}) {
  const h = { Accept: 'application/json', ...headers };
  if (body !== undefined) h['Content-Type'] = 'application/json';
  if (currentToken) h.Authorization = `Bearer ${currentToken}`;
  if (idempotencyKey) h['Idempotency-Key'] = idempotencyKey;
  const res = await fetch(path, { method, headers: h, body: body === undefined ? undefined : JSON.stringify(body) });
  const text = await res.text();
  const data = text ? JSON.parse(text) : null;
  if (!res.ok) throw new ApiError(res.status, data);
  return { data, status: res.status, replayed: res.headers.get('Idempotent-Replayed') === 'true' };
}

const get = (p) => request('GET', p).then((r) => r.data);

export const api = {
  register: (b) => request('POST', '/api/v1/auth/register', { body: b }).then((r) => r.data),
  login: (b) => request('POST', '/api/v1/auth/login', { body: b }).then((r) => r.data),
  events: (page = 0, category) => get(`/api/v1/events?page=${page}&size=12${category ? `&category=${category}` : ''}`),
  event: (id) => get(`/api/v1/events/${id}`),
  screening: (id) => get(`/api/v1/screenings/${id}`),
  seats: (id) => get(`/api/v1/screenings/${id}/seats`),
  hold: (screeningId, seatIds, key = newIdempotencyKey()) =>
    request('POST', `/api/v1/screenings/${screeningId}/holds`, { body: { screeningSeatIds: seatIds }, idempotencyKey: key }),
  getHold: (id) => get(`/api/v1/holds/${id}`),
  releaseHold: (id) => request('DELETE', `/api/v1/holds/${id}`),
  confirm: (holdId, paymentMethodToken, key) =>
    request('POST', '/api/v1/bookings/confirm', { body: { holdId, paymentMethodToken }, idempotencyKey: key }),
  bookings: (page = 0) => get(`/api/v1/bookings?page=${page}&size=20`),
  booking: (id) => get(`/api/v1/bookings/${id}`),
  cancel: (id) => request('POST', `/api/v1/bookings/${id}/cancel`, { idempotencyKey: newIdempotencyKey() }).then((r) => r.data),
  notifications: () => get('/api/v1/notifications?size=50'),
  admin: {
    venues: () => get('/api/v1/admin/venues'),
    createVenue: (b) => request('POST', '/api/v1/admin/venues', { body: b }).then((r) => r.data),
    createEvent: (b) => request('POST', '/api/v1/admin/events', { body: b }).then((r) => r.data),
    createScreening: (b) => request('POST', '/api/v1/admin/screenings', { body: b }).then((r) => r.data),
    assignSeats: (id, b) => request('POST', `/api/v1/admin/screenings/${id}/seats`, { body: b }).then((r) => r.data),
  },
};

export const money = (amount, currency = 'INR') =>
  new Intl.NumberFormat('en-IN', { style: 'currency', currency, maximumFractionDigits: 0 }).format(Number(amount));

export const when = (iso) =>
  new Intl.DateTimeFormat(undefined, { weekday: 'short', day: 'numeric', month: 'short', hour: '2-digit', minute: '2-digit' })
    .format(new Date(iso));
