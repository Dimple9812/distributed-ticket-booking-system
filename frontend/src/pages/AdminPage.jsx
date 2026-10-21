import { useState } from 'react';
import { Link } from 'react-router-dom';
import { api } from '../api.js';
import { ErrorBox, useLoad } from '../components.jsx';

const SEAT_TYPES = ['STANDARD', 'PREMIUM', 'VIP'];
const CATEGORIES = ['MOVIE', 'CONCERT', 'THEATRE', 'SPORTS', 'COMEDY', 'OTHER'];

function useAction() {
  const [state, setState] = useState({ busy: false, error: null, result: null });
  const run = async (fn) => {
    setState({ busy: true, error: null, result: null });
    try {
      const result = await fn();
      setState({ busy: false, error: null, result });
      return result;
    } catch (error) {
      setState({ busy: false, error, result: null });
      return null;
    }
  };
  return [state, run];
}

export default function AdminPage() {
  const venues = useLoad(() => api.admin.venues(), []);
  const events = useLoad(() => api.events(0), []);
  return (
    <>
      <h1>Admin</h1>
      <p className="muted">Create a venue and an event, schedule a screening, then put its seats on sale.</p>
      <div className="admin-grid">
        <VenueForm onCreated={venues.reload} />
        <EventForm onCreated={events.reload} />
        <ScreeningForm venues={venues.data || []} events={events.data?.content || []} />
      </div>
    </>
  );
}

function VenueForm({ onCreated }) {
  const [form, setForm] = useState({ name: '', city: '', address: '' });
  const [rows, setRows] = useState([{ rowLabel: 'A', seatCount: 10, seatType: 'STANDARD' }, { rowLabel: 'B', seatCount: 10, seatType: 'PREMIUM' }]);
  const [state, run] = useAction();
  const setRow = (i, k, v) => setRows(rows.map((r, j) => (j === i ? { ...r, [k]: v } : r)));

  const submit = (e) => {
    e.preventDefault();
    run(async () => {
      const v = await api.admin.createVenue({ ...form, rows: rows.map((r) => ({ ...r, seatCount: Number(r.seatCount) })) });
      onCreated();
      return `Venue #${v.id} created with ${v.seatCount} seats`;
    });
  };

  return (
    <form className="card form" onSubmit={submit}>
      <h3>1 · Venue & seat layout</h3>
      <label>Name<input required value={form.name} onChange={(e) => setForm({ ...form, name: e.target.value })} /></label>
      <label>City<input required value={form.city} onChange={(e) => setForm({ ...form, city: e.target.value })} /></label>
      <label>Address<input required value={form.address} onChange={(e) => setForm({ ...form, address: e.target.value })} /></label>
      <div className="small muted">Rows</div>
      {rows.map((r, i) => (
        <div className="row-editor" key={i}>
          <input aria-label="Row label" value={r.rowLabel} maxLength={2} onChange={(e) => setRow(i, 'rowLabel', e.target.value.toUpperCase())} />
          <input aria-label="Seats" type="number" min={1} max={100} value={r.seatCount} onChange={(e) => setRow(i, 'seatCount', e.target.value)} />
          <select aria-label="Seat type" value={r.seatType} onChange={(e) => setRow(i, 'seatType', e.target.value)}>
            {SEAT_TYPES.map((t) => <option key={t}>{t}</option>)}
          </select>
          <button type="button" className="btn ghost" onClick={() => setRows(rows.filter((_, j) => j !== i))} disabled={rows.length === 1}>×</button>
        </div>
      ))}
      <button type="button" className="btn ghost" onClick={() =>
        setRows([...rows, { rowLabel: String.fromCharCode(65 + rows.length), seatCount: 10, seatType: 'STANDARD' }])}>+ Add row</button>
      <ErrorBox error={state.error} />
      {state.result && <p className="ok small">{state.result}</p>}
      <button className="btn" disabled={state.busy}>Create venue</button>
    </form>
  );
}

function EventForm({ onCreated }) {
  const [form, setForm] = useState({ title: '', description: '', category: 'MOVIE', language: 'English', durationMinutes: 120 });
  const [state, run] = useAction();
  const set = (k) => (e) => setForm({ ...form, [k]: e.target.value });
  const submit = (e) => {
    e.preventDefault();
    run(async () => {
      const ev = await api.admin.createEvent({ ...form, durationMinutes: Number(form.durationMinutes) });
      onCreated();
      return `Event #${ev.id} created`;
    });
  };
  return (
    <form className="card form" onSubmit={submit}>
      <h3>2 · Event</h3>
      <label>Title<input required value={form.title} onChange={set('title')} /></label>
      <label>Description<textarea rows={3} value={form.description} onChange={set('description')} /></label>
      <label>Category<select value={form.category} onChange={set('category')}>{CATEGORIES.map((c) => <option key={c}>{c}</option>)}</select></label>
      <label>Language<input value={form.language} onChange={set('language')} /></label>
      <label>Duration (minutes)<input type="number" min={1} max={1440} value={form.durationMinutes} onChange={set('durationMinutes')} /></label>
      <ErrorBox error={state.error} />
      {state.result && <p className="ok small">{state.result}</p>}
      <button className="btn" disabled={state.busy}>Create event</button>
    </form>
  );
}

function ScreeningForm({ venues, events }) {
  const tomorrow = new Date(Date.now() + 86400000);
  tomorrow.setMinutes(0, 0, 0);
  const local = new Date(tomorrow.getTime() - tomorrow.getTimezoneOffset() * 60000).toISOString().slice(0, 16);
  const [form, setForm] = useState({ eventId: '', venueId: '', startsAt: local });
  const [prices, setPrices] = useState({ STANDARD: 250, PREMIUM: 400, VIP: 750 });
  const [state, run] = useAction();
  const [created, setCreated] = useState(null);

  const submit = (e) => {
    e.preventDefault();
    run(async () => {
      const s = await api.admin.createScreening({
        eventId: Number(form.eventId), venueId: Number(form.venueId), startsAt: new Date(form.startsAt).toISOString(),
      });
      const priceBySeatType = Object.fromEntries(Object.entries(prices).map(([k, v]) => [k, Number(v)]));
      const a = await api.admin.assignSeats(s.id, { priceBySeatType });
      setCreated(s.id);
      return `Screening #${s.id} scheduled, ${a.assignedSeats} seats on sale`;
    });
  };

  return (
    <form className="card form" onSubmit={submit}>
      <h3>3 · Screening & prices</h3>
      <label>Event
        <select required value={form.eventId} onChange={(e) => setForm({ ...form, eventId: e.target.value })}>
          <option value="">Choose…</option>
          {events.map((ev) => <option key={ev.id} value={ev.id}>{ev.title}</option>)}
        </select>
      </label>
      <label>Venue
        <select required value={form.venueId} onChange={(e) => setForm({ ...form, venueId: e.target.value })}>
          <option value="">Choose…</option>
          {venues.map((v) => <option key={v.id} value={v.id}>{v.name} · {v.city} ({v.seatCount} seats)</option>)}
        </select>
      </label>
      <label>Starts at<input required type="datetime-local" value={form.startsAt} onChange={(e) => setForm({ ...form, startsAt: e.target.value })} /></label>
      <div className="small muted">Price per seat type (INR) — every seat type in the venue needs a price</div>
      {SEAT_TYPES.map((t) => (
        <label key={t} className="inline">{t.toLowerCase()}
          <input type="number" min={1} value={prices[t]} onChange={(e) => setPrices({ ...prices, [t]: e.target.value })} />
        </label>
      ))}
      <ErrorBox error={state.error} />
      {state.result && <p className="ok small">{state.result} · <Link to={`/screenings/${created}`}>open seat map</Link></p>}
      <button className="btn" disabled={state.busy}>Schedule & open for sale</button>
    </form>
  );
}
