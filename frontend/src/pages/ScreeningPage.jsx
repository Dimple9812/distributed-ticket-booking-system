import { useEffect, useMemo, useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { api, money, when } from '../api.js';
import { useAuth } from '../auth.jsx';
import { ErrorBox, Loading, useLoad } from '../components.jsx';

const MAX_SEATS = 10;

export default function ScreeningPage() {
  const { screeningId } = useParams();
  const { user } = useAuth();
  const navigate = useNavigate();
  const screening = useLoad(() => api.screening(screeningId), [screeningId]);
  const seats = useLoad(() => api.seats(screeningId), [screeningId]);
  const [selected, setSelected] = useState([]);
  const [error, setError] = useState(null);
  const [busy, setBusy] = useState(false);

  // Live-ish availability: refresh the seat map every 5 seconds.
  useEffect(() => {
    const id = setInterval(seats.reload, 5000);
    return () => clearInterval(id);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [screeningId]);

  // Drop selections that someone else took in the meantime.
  useEffect(() => {
    if (!seats.data) return;
    const free = new Set(seats.data.seats.filter((s) => s.status === 'AVAILABLE').map((s) => s.screeningSeatId));
    setSelected((sel) => sel.filter((id) => free.has(id)));
  }, [seats.data]);

  const rows = useMemo(() => {
    const byRow = new Map();
    (seats.data?.seats || []).forEach((s) => {
      if (!byRow.has(s.rowLabel)) byRow.set(s.rowLabel, []);
      byRow.get(s.rowLabel).push(s);
    });
    return [...byRow.entries()];
  }, [seats.data]);

  const seatById = useMemo(() => new Map((seats.data?.seats || []).map((s) => [s.screeningSeatId, s])), [seats.data]);
  const total = selected.reduce((sum, id) => sum + Number(seatById.get(id)?.price || 0), 0);

  if (screening.loading && !screening.data) return <Loading />;
  if (screening.error) return <ErrorBox error={screening.error} />;
  const s = screening.data;

  const toggle = (seat) => {
    if (seat.status !== 'AVAILABLE') return;
    setError(null);
    setSelected((sel) => sel.includes(seat.screeningSeatId)
      ? sel.filter((x) => x !== seat.screeningSeatId)
      : sel.length >= MAX_SEATS ? sel : [...sel, seat.screeningSeatId]);
  };

  const hold = async () => {
    if (!user) { navigate('/login', { state: { from: `/screenings/${screeningId}` } }); return; }
    setBusy(true);
    setError(null);
    try {
      const { data } = await api.hold(screeningId, selected);
      navigate(`/checkout/${data.holdId}`);
    } catch (e) {
      setError(e);
      seats.reload();
    } finally {
      setBusy(false);
    }
  };

  return (
    <>
      <Link to={`/events/${s.eventId}`} className="back">← {s.eventTitle}</Link>
      <section className="detail-head">
        <h1>{s.eventTitle}</h1>
        <p className="muted">{when(s.startsAt)} · {s.venueName}, {s.city}</p>
        <p className="small muted">{s.availableSeats} of {s.totalSeats} seats available</p>
      </section>

      <div className="booking-layout">
        <div className="card seatmap-card">
          <div className="screen">SCREEN / STAGE</div>
          {seats.loading && !seats.data ? <Loading /> : (
            <div className="seatmap">
              {rows.map(([row, list]) => (
                <div className="seat-row" key={row}>
                  <span className="row-label">{row}</span>
                  {list.map((seat) => {
                    const isSel = selected.includes(seat.screeningSeatId);
                    return (
                      <button
                        key={seat.screeningSeatId}
                        className={`seat ${seat.seatType.toLowerCase()} ${seat.status.toLowerCase()} ${isSel ? 'selected' : ''}`}
                        disabled={seat.status !== 'AVAILABLE'}
                        onClick={() => toggle(seat)}
                        title={`${row}${seat.seatNumber} · ${seat.seatType} · ${money(seat.price)} · ${seat.status}`}
                        aria-pressed={isSel}
                      >
                        {seat.seatNumber}
                      </button>
                    );
                  })}
                </div>
              ))}
            </div>
          )}
          <div className="legend small">
            <span><i className="seat-dot standard" /> Standard</span>
            <span><i className="seat-dot premium" /> Premium</span>
            <span><i className="seat-dot vip" /> VIP</span>
            <span><i className="seat-dot held" /> Held</span>
            <span><i className="seat-dot booked" /> Sold</span>
            <span><i className="seat-dot sel" /> Your pick</span>
          </div>
        </div>

        <aside className="card summary">
          <h3>Your selection</h3>
          {selected.length === 0 ? <p className="muted small">Tap up to {MAX_SEATS} available seats.</p> : (
            <ul className="lines">
              {selected.map((id) => {
                const seat = seatById.get(id);
                return seat && (
                  <li key={id}><span>{seat.rowLabel}{seat.seatNumber} <span className="muted small">{seat.seatType.toLowerCase()}</span></span>
                    <span>{money(seat.price)}</span></li>
                );
              })}
            </ul>
          )}
          <div className="total"><span>Total</span><strong>{money(total)}</strong></div>
          <ErrorBox error={error} />
          <button className="btn wide" disabled={selected.length === 0 || busy} onClick={hold}>
            {busy ? 'Holding…' : user ? `Hold ${selected.length || ''} seat${selected.length === 1 ? '' : 's'} for 5 minutes` : 'Sign in to book'}
          </button>
          <p className="small muted">Holds are all-or-nothing: if any seat was just taken, none are held.</p>
        </aside>
      </div>

      {user && seats.data && <RaceDemo screeningId={screeningId} seats={seats.data.seats} onDone={seats.reload} />}
    </>
  );
}

/**
 * Fires N simultaneous hold requests for the same seat (each with its own Idempotency-Key,
 * so idempotency cannot dedupe them) and shows that exactly one wins.
 */
function RaceDemo({ screeningId, seats, onDone }) {
  const [results, setResults] = useState(null);
  const [running, setRunning] = useState(false);
  const free = seats.filter((s) => s.status === 'AVAILABLE');

  const run = async () => {
    if (free.length === 0) return;
    const seat = free[Math.floor(Math.random() * free.length)];
    setRunning(true);
    setResults(null);
    const attempts = Array.from({ length: 15 }, () =>
      api.hold(screeningId, [seat.screeningSeatId]).then(
        (r) => ({ ok: true, holdId: r.data.holdId }),
        (e) => ({ ok: false, code: e.code || e.status }),
      ));
    const out = await Promise.all(attempts);
    // Give the seat back so the demo doesn't eat inventory.
    await Promise.all(out.filter((r) => r.ok).map((r) => api.releaseHold(r.holdId).catch(() => {})));
    setResults({ seat, out });
    setRunning(false);
    onDone();
  };

  const wins = results?.out.filter((r) => r.ok).length ?? 0;
  const codes = results ? Object.entries(results.out.filter((r) => !r.ok)
    .reduce((acc, r) => ({ ...acc, [r.code]: (acc[r.code] || 0) + 1 }), {})) : [];

  return (
    <section className="card race">
      <div>
        <h3>Try to break it: 15 simultaneous holds on one seat</h3>
        <p className="small muted">
          Sends 15 hold requests for a random free seat at the same moment, each with a different idempotency key.
          The winner's hold is released right after, so the seat stays on sale.
        </p>
      </div>
      <button className="btn ghost" disabled={running || free.length === 0} onClick={run}>
        {running ? 'Racing…' : 'Run the race'}
      </button>
      {results && (
        <div className="race-result">
          <p>
            Seat <strong>{results.seat.rowLabel}{results.seat.seatNumber}</strong>:{' '}
            <strong className={wins === 1 ? 'ok' : 'bad'}>{wins} succeeded</strong>,{' '}
            {codes.map(([code, n]) => `${n} rejected with ${code}`).join(', ') || 'none rejected'}.
          </p>
          <div className="race-bars">
            {results.out.map((r, i) => <span key={i} className={r.ok ? 'win' : 'lose'} title={r.ok ? 'held' : String(r.code)} />)}
          </div>
        </div>
      )}
    </section>
  );
}
