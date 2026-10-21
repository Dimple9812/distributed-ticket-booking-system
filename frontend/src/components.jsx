import { useEffect, useState } from 'react';

export function ErrorBox({ error }) {
  if (!error) return null;
  return (
    <div className="alert error" role="alert">
      <strong>{error.code ? error.code.replaceAll('_', ' ').toLowerCase() : 'error'}</strong>
      <span>{error.message}</span>
      {error.fieldErrors?.length > 0 && (
        <ul>{error.fieldErrors.map((f) => <li key={f.field + f.message}>{f.field}: {f.message}</li>)}</ul>
      )}
    </div>
  );
}

export function Loading({ label = 'Loading…' }) {
  return <p className="muted">{label}</p>;
}

/** Loads data with a promise-returning function; reload() re-runs it. */
export function useLoad(fn, deps) {
  const [state, setState] = useState({ data: null, error: null, loading: true });
  const [tick, setTick] = useState(0);
  useEffect(() => {
    let alive = true;
    setState((s) => ({ ...s, loading: true }));
    fn().then(
      (data) => alive && setState({ data, error: null, loading: false }),
      (error) => alive && setState({ data: null, error, loading: false }),
    );
    return () => { alive = false; };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [...deps, tick]);
  return { ...state, reload: () => setTick((t) => t + 1) };
}

export function Countdown({ until, onExpire }) {
  const [now, setNow] = useState(Date.now());
  useEffect(() => {
    const id = setInterval(() => setNow(Date.now()), 500);
    return () => clearInterval(id);
  }, []);
  const left = Math.max(0, new Date(until).getTime() - now);
  useEffect(() => {
    if (left === 0 && onExpire) onExpire();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [left === 0]);
  const m = Math.floor(left / 60000);
  const s = Math.floor((left % 60000) / 1000).toString().padStart(2, '0');
  return <span className={`countdown ${left < 60000 ? 'urgent' : ''}`}>{m}:{s}</span>;
}

export function StatusPill({ status }) {
  return <span className={`pill ${status?.toLowerCase()}`}>{status}</span>;
}
