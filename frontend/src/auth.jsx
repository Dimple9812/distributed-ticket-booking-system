import { createContext, useCallback, useContext, useMemo, useState } from 'react';
import { api, loadAuth, saveAuth, setToken } from './api.js';

const AuthContext = createContext(null);

export function AuthProvider({ children }) {
  const [auth, setAuth] = useState(loadAuth);

  const apply = useCallback((value) => {
    setToken(value?.accessToken || null);
    saveAuth(value);
    setAuth(value);
  }, []);

  const value = useMemo(() => ({
    user: auth?.user || null,
    isAdmin: auth?.user?.role === 'ADMIN',
    login: async (email, password) => apply(await api.login({ email, password })),
    register: async (email, password, fullName) => apply(await api.register({ email, password, fullName })),
    logout: () => apply(null),
  }), [auth, apply]);

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export const useAuth = () => useContext(AuthContext);
