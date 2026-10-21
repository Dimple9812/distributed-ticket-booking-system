import { Link } from 'react-router-dom';
import { api, money, when } from '../api.js';
import { ErrorBox, Loading, StatusPill, useLoad } from '../components.jsx';

export default function BookingsPage() {
  const { data, error, loading } = useLoad(() => api.bookings(), []);
  if (loading) return <Loading />;
  return (
    <>
      <h1>My bookings</h1>
      <ErrorBox error={error} />
      {data?.content.length === 0 && <p className="muted">No bookings yet. <Link to="/">Find an event</Link>.</p>}
      <div className="list">
        {data?.content.map((b) => (
          <Link key={b.bookingId} to={`/bookings/${b.bookingId}`} className="row-link">
            <div>
              <strong>{b.seatCount} seat{b.seatCount === 1 ? '' : 's'} · {money(b.totalAmount, b.currency)}</strong>
              <div className="small muted">Booked {when(b.confirmedAt)} · #{b.bookingId.slice(0, 8)}</div>
            </div>
            <StatusPill status={b.status} />
          </Link>
        ))}
      </div>
    </>
  );
}
