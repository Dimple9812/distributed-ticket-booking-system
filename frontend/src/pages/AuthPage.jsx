import { useState } from 'react';
import { useLocation, useNavigate } from 'react-router-dom';
import { useAuth } from '../auth.jsx';
import { ErrorBox } from '../components.jsx';

export default function AuthPage() {
  const { login, register } = useAuth();
  const navigate = useNavigate();
  const location = useLocation();
  const [mode, setMode] = useState('register');
  const [form, setForm] = useState({ fullName: '', email: '', password: '' });
  const [error, setError] = useState(null);
  const [busy, setBusy] = useState(false);

  const set = (k) => (e) => setForm({ ...form, [k]: e.target.value });

  const submit = async (e) => {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      if (mode === 'login') await login(form.email, form.password);
      else await register(form.email, form.password, form.fullName);
      navigate(location.state?.from || '/', { replace: true });
    } catch (err) {
      setError(err);
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="narrow">
      <div className="card">
        <div className="tabs">
          <button className={mode === 'register' ? 'on' : ''} onClick={() => setMode('register')}>Create account</button>
          <button className={mode === 'login' ? 'on' : ''} onClick={() => setMode('login')}>Sign in</button>
        </div>
        <form onSubmit={submit} className="form">
          {mode === 'register' && (
            <label>Full name<input required maxLength={120} value={form.fullName} onChange={set('fullName')} autoComplete="name" /></label>
          )}
          <label>Email<input required type="email" value={form.email} onChange={set('email')} autoComplete="email" /></label>
          <label>Password
            <input required type="password" minLength={mode === 'register' ? 8 : 1} maxLength={72} value={form.password}
              onChange={set('password')} autoComplete={mode === 'register' ? 'new-password' : 'current-password'} />
          </label>
          {mode === 'register' && <p className="small muted">At least 8 characters. Use a throwaway email — this is a demo.</p>}
          <ErrorBox error={error} />
          <button className="btn wide" disabled={busy}>{busy ? 'Please wait…' : mode === 'login' ? 'Sign in' : 'Create account'}</button>
        </form>
      </div>
    </div>
  );
}
