import { useMemo, useState } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { api, money, newIdempotencyKey } from '../api.js';
import { Countdown, ErrorBox, Loading, StatusPill, useLoad } from '../components.jsx';

const CARDS = [
  { token: 'pm_card_visa', label: 'Test Visa ···· 4242', note: 'approved' },
  { token: 'pm_card_mastercard', label: 'Test Mastercard ···· 5555', note: 'approved' },
  { token: 'pm_card_declined', label: 'Test card that is declined', note: 'declined' },
];

export default function CheckoutPage() {
  const { holdId } = useParams();
  const navigate = useNavigate();
  const hold = useLoad(() => api.getHold(holdId), [holdId]);
  const [card, setCard] = useState(CARDS[0].token);
  const [error, setError] = useState(null);
  const [busy, setBusy] = useState(false);
  const [expired, setExpired] = useState(false);
  // One key per (hold, card) choice: double-clicks and network retries reuse it and are replayed, not repeated.
  const key = useMemo(() => newIdempotencyKey(), [holdId, card]);

  if (hold.loading && !hold.data) return <Loading />;
  if (hold.error) return <ErrorBox error={hold.error} />;
  const h = hold.data;
  const active = h.status === 'ACTIVE' && !expired;

  const pay = async () => {
    setBusy(true);
    setError(null);
    try {
      const { data } = await api.confirm(holdId, card, key);
      navigate(`/bookings/${data.bookingId}`, { state: { justBooked: true } });
    } catch (e) {
      setError(e);
      if (e.code === 'HOLD_EXPIRED') setExpired(true);
    } finally {
      setBusy(false);
    }
  };

  const release = async () => {
    await api.releaseHold(holdId).catch(() => {});
    navigate(`/screenings/${h.screeningId}`);
  };

  return (
    <div className="narrow">
      <Link to={`/screenings/${h.screeningId}`} className="back">← Back to seats</Link>
      <div className="card">
        <div className="checkout-head">
          <h1>Checkout</h1>
          {active ? <span className="small">Seats held for <Countdown until={h.expiresAt} onExpire={() => setExpired(true)} /></span>
            : <StatusPill status={expired && h.status === 'ACTIVE' ? 'EXPIRED' : h.status} />}
        </div>
        <ul className="lines">
          {h.seats.map((s) => (
            <li key={s.screeningSeatId}><span>{s.seatLabel} <span className="muted small">{s.seatType.toLowerCase()}</span></span>
              <span>{money(s.price, h.currency)}</span></li>
          ))}
        </ul>
        <div className="total"><span>Total</span><strong>{money(h.totalAmount, h.currency)}</strong></div>

        {active && (
          <>
            <h3>Payment method <span className="small muted">(simulated)</span></h3>
            <div className="radio-list">
              {CARDS.map((c) => (
                <label key={c.token} className={`radio ${card === c.token ? 'on' : ''}`}>
                  <input type="radio" name="card" value={c.token} checked={card === c.token} onChange={() => setCard(c.token)} />
                  <span>{c.label}</span><span className={`small ${c.note === 'declined' ? 'bad' : 'muted'}`}>{c.note}</span>
                </label>
              ))}
            </div>
          </>
        )}

        <ErrorBox error={error} />
        {active ? (
          <div className="actions">
            <button className="btn ghost" onClick={release} disabled={busy}>Release seats</button>
            <button className="btn" onClick={pay} disabled={busy}>{busy ? 'Confirming…' : `Pay ${money(h.totalAmount, h.currency)}`}</button>
          </div>
        ) : (
          <p className="muted">This hold is no longer active. <Link to={`/screenings/${h.screeningId}`}>Pick seats again</Link>.</p>
        )}
      </div>
    </div>
  );
}
