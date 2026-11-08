import { useEffect, useState } from 'react';
import { clearToken, setUnauthorizedHandler, type Me } from './api/client';
import LoginPage from './pages/LoginPage';
import DashboardPage from './pages/DashboardPage';

export default function App() {
  const [me, setMe] = useState<Me | null>(null);
  const [notice, setNotice] = useState<string | null>(null);

  useEffect(() => {
    setUnauthorizedHandler(() => {
      setMe(null);
      setNotice('Your session expired. Please sign in again.');
    });
  }, []);

  if (!me) {
    return (
      <LoginPage
        notice={notice}
        onLogin={(user) => {
          setNotice(null);
          setMe(user);
        }}
      />
    );
  }

  return (
    <DashboardPage
      me={me}
      onLogout={() => {
        clearToken();
        setMe(null);
      }}
    />
  );
}
