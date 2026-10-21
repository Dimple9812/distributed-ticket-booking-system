import { Link, NavLink, Navigate, Route, Routes } from 'react-router-dom';
import { useAuth } from './auth.jsx';
import EventsPage from './pages/EventsPage.jsx';
import EventPage from './pages/EventPage.jsx';
import ScreeningPage from './pages/ScreeningPage.jsx';
import CheckoutPage from './pages/CheckoutPage.jsx';
import BookingsPage from './pages/BookingsPage.jsx';
import BookingPage from './pages/BookingPage.jsx';
import NotificationsPage from './pages/NotificationsPage.jsx';
import AuthPage from './pages/AuthPage.jsx';
import AdminPage from './pages/AdminPage.jsx';
import AboutPage from './pages/AboutPage.jsx';

function RequireAuth({ children, admin = false }) {
  const { user, isAdmin } = useAuth();
  if (!user) return <Navigate to="/login" replace />;
  if (admin && !isAdmin) return <Navigate to="/" replace />;
  return children;
}

export default function App() {
  const { user, isAdmin, logout } = useAuth();
  return (
    <div className="shell">
      <header className="topbar">
        <Link to="/" className="brand">
          <img src="/favicon.svg" alt="" width="28" height="28" />
          <span>Seatline</span>
        </Link>
        <nav>
          <NavLink to="/" end>Events</NavLink>
          {user && <NavLink to="/bookings">My bookings</NavLink>}
          {user && <NavLink to="/notifications">Notifications</NavLink>}
          {isAdmin && <NavLink to="/admin">Admin</NavLink>}
          <NavLink to="/about">How it works</NavLink>
        </nav>
        <div className="account">
          {user ? (
            <>
              <span className="muted small">{user.fullName}{isAdmin ? ' · admin' : ''}</span>
              <button className="btn ghost" onClick={logout}>Sign out</button>
            </>
          ) : (
            <Link className="btn" to="/login">Sign in</Link>
          )}
        </div>
      </header>

      <main className="content">
        <Routes>
          <Route path="/" element={<EventsPage />} />
          <Route path="/events/:eventId" element={<EventPage />} />
          <Route path="/screenings/:screeningId" element={<ScreeningPage />} />
          <Route path="/checkout/:holdId" element={<RequireAuth><CheckoutPage /></RequireAuth>} />
          <Route path="/bookings" element={<RequireAuth><BookingsPage /></RequireAuth>} />
          <Route path="/bookings/:bookingId" element={<RequireAuth><BookingPage /></RequireAuth>} />
          <Route path="/notifications" element={<RequireAuth><NotificationsPage /></RequireAuth>} />
          <Route path="/admin" element={<RequireAuth admin><AdminPage /></RequireAuth>} />
          <Route path="/login" element={<AuthPage />} />
          <Route path="/about" element={<AboutPage />} />
          <Route path="*" element={<Navigate to="/" replace />} />
        </Routes>
      </main>

      <footer className="footer muted small">
        Demo project · payments are simulated, no real money moves ·{' '}
        <a href="/swagger-ui.html" target="_blank" rel="noreferrer">API docs</a> ·{' '}
        <a href="https://github.com/Dimple9812/distributed-ticket-booking-system" target="_blank" rel="noreferrer">Source</a>
      </footer>
    </div>
  );
}
