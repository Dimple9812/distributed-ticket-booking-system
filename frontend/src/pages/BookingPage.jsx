import { useState } from 'react';
import { Link, useLocation, useParams } from 'react-router-dom';
import { api, money, when } from '../api.js';
import { ErrorBox, Loading, StatusPill, useLoad } from '../components.jsx';

export default function BookingPage() {
  const { bookingId } = useParams();
  const location = useLocation();
  const booking = useLoad(() => api.booking(bookingId), [bookingId]);
  const [error, setError] = useState(null);
  const [busy, setBusy] = useState(false);

  if (booking.loading && !booking.data) return <Loading />;
  if (booking.error) return <ErrorBox error={booking.error} />;
  const b = booking.data;

  const cancel = async () => {
    if (!window.confirm('Cancel this booking? The seats go back on sale and the payment is refunded.')) return;
    setBusy(true);
    setError(null);
    try {
      await api.cancel(bookingId);
      booking.reload();
    } catch (e) {
      setError(e);
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="narrow">
      <Link to="/bookings" className="back">← My bookings</Link>
      {location.state?.justBooked && b.status === 'CONFIRMED' && (
        <div className="alert success">
          <strong>You're booked!</strong>
          <span>A confirmation notification is on its way (sent asynchronously through Kafka).</span>
        </div>
      )}
      <div className="card ticket">
        <div className="checkout-head">
          <h1>{b.screening.eventTitle}</h1>
          <StatusPill status={b.status} />
        </div>
        <p className="muted">{when(b.screening.startsAt)} · {b.screening.venueName}, {b.screening.city}</p>
        <ul className="lines">
          {b.seats.map((s) => (
            <li key={s.screeningSeatId}><span>{s.seatLabel} <span className="muted small">{s.seatType.toLowerCase()}</span></span>
              <span>{money(s.price, b.currency)}</span></li>
          ))}
        </ul>
        <div className="total"><span>Total paid</span><strong>{money(b.totalAmount, b.currency)}</strong></div>
        <dl className="meta small">
          <dt>Booking</dt><dd className="mono">{b.bookingId}</dd>
          <dt>Payment ref</dt><dd className="mono">{b.paymentReference}</dd>
          <dt>Confirmed</dt><dd>{when(b.confirmedAt)}</dd>
          {b.cancelledAt && (<><dt>Cancelled</dt><dd>{when(b.cancelledAt)}</dd></>)}
        </dl>
        <ErrorBox error={error} />
        {b.status === 'CONFIRMED' && (
          <div className="actions">
            <span className="small muted">Cancellable until 2 hours before the show.</span>
            <button className="btn danger" onClick={cancel} disabled={busy}>{busy ? 'Cancelling…' : 'Cancel booking'}</button>
          </div>
        )}
      </div>
    </div>
  );
}
