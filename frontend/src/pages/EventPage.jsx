import { Link, useParams } from 'react-router-dom';
import { api, when } from '../api.js';
import { ErrorBox, Loading, useLoad } from '../components.jsx';

export default function EventPage() {
  const { eventId } = useParams();
  const { data, error, loading } = useLoad(() => api.event(eventId), [eventId]);
  if (loading) return <Loading />;
  if (error) return <ErrorBox error={error} />;
  const { event, upcomingScreenings } = data;

  return (
    <>
      <Link to="/" className="back">← All events</Link>
      <section className="detail-head">
        <span className={`tag ${event.category.toLowerCase()}`}>{event.category.toLowerCase()}</span>
        <h1>{event.title}</h1>
        <p className="muted">{event.language ? `${event.language} · ` : ''}{event.durationMinutes} minutes</p>
        {event.description && <p className="lead">{event.description}</p>}
      </section>

      <h2>Upcoming screenings</h2>
      {upcomingScreenings.length === 0 && <p className="muted">No upcoming screenings.</p>}
      <div className="list">
        {upcomingScreenings.map((s) => (
          <Link key={s.screeningId} to={`/screenings/${s.screeningId}`} className="row-link">
            <div>
              <strong>{when(s.startsAt)}</strong>
              <div className="small muted">{s.venueName} · {s.city}</div>
            </div>
            <span className="btn">Choose seats</span>
          </Link>
        ))}
      </div>
    </>
  );
}
