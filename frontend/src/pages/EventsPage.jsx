import { useState } from 'react';
import { Link } from 'react-router-dom';
import { api } from '../api.js';
import { ErrorBox, Loading, useLoad } from '../components.jsx';

const CATEGORIES = ['MOVIE', 'CONCERT', 'THEATRE', 'SPORTS', 'COMEDY', 'OTHER'];

export default function EventsPage() {
  const [category, setCategory] = useState('');
  const [page, setPage] = useState(0);
  const { data, error, loading } = useLoad(() => api.events(page, category), [page, category]);

  return (
    <>
      <section className="hero">
        <h1>Every seat sold exactly once.</h1>
        <p>
          Pick a show, hold your seats for five minutes, and confirm. Behind the scenes, Redis locks and
          PostgreSQL constraints make sure two people can never buy the same seat.
        </p>
      </section>

      <div className="filters">
        <button className={`chip ${category === '' ? 'on' : ''}`} onClick={() => { setCategory(''); setPage(0); }}>All</button>
        {CATEGORIES.map((c) => (
          <button key={c} className={`chip ${category === c ? 'on' : ''}`} onClick={() => { setCategory(c); setPage(0); }}>
            {c.toLowerCase()}
          </button>
        ))}
      </div>

      <ErrorBox error={error} />
      {loading && !data ? <Loading /> : (
        <>
          <div className="grid">
            {data?.content.map((e) => (
              <Link key={e.id} to={`/events/${e.id}`} className="card event-card">
                <span className={`tag ${e.category.toLowerCase()}`}>{e.category.toLowerCase()}</span>
                <h3>{e.title}</h3>
                <p className="muted clamp">{e.description || 'No description'}</p>
                <p className="small muted">{e.language ? `${e.language} · ` : ''}{e.durationMinutes} min</p>
              </Link>
            ))}
          </div>
          {data?.content.length === 0 && <p className="muted">No events in this category yet.</p>}
          {data?.totalPages > 1 && (
            <div className="pager">
              <button className="btn ghost" disabled={page === 0} onClick={() => setPage(page - 1)}>Previous</button>
              <span className="small muted">Page {page + 1} of {data.totalPages}</span>
              <button className="btn ghost" disabled={page + 1 >= data.totalPages} onClick={() => setPage(page + 1)}>Next</button>
            </div>
          )}
        </>
      )}
    </>
  );
}
