import { useEffect } from 'react';
import { api, when } from '../api.js';
import { ErrorBox, Loading, useLoad } from '../components.jsx';

export default function NotificationsPage() {
  const { data, error, loading, reload } = useLoad(() => api.notifications(), []);
  useEffect(() => {
    const id = setInterval(reload, 4000);
    return () => clearInterval(id);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  return (
    <>
      <h1>Notifications</h1>
      <p className="muted small">
        Produced asynchronously: booking change → outbox row → Kafka → consumer. This page refreshes every few seconds.
      </p>
      <ErrorBox error={error} />
      {loading && !data ? <Loading /> : data?.content.length === 0 ? <p className="muted">Nothing yet.</p> : (
        <div className="list">
          {data?.content.map((n) => (
            <div key={n.id} className="card note">
              <div className="checkout-head">
                <strong>{n.title}</strong>
                <span className="small muted">{when(n.createdAt)}</span>
              </div>
              <p className="small">{n.message}</p>
            </div>
          ))}
        </div>
      )}
    </>
  );
}
