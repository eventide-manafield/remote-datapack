import { useEffect, useState } from 'react';

type Theme = 'system' | 'light' | 'dark';
const key = 'remotedatapack.theme';
const preference = window.matchMedia('(prefers-color-scheme: dark)');
function savedTheme(): Theme {
  try { const value = localStorage.getItem(key); if (value === 'light' || value === 'dark') return value; } catch { /* Storage is optional. */ }
  return 'system';
}
function applyTheme(theme: Theme) {
  const resolved = theme === 'system' ? (preference.matches ? 'dark' : 'light') : theme;
  document.documentElement.dataset.theme = resolved;
  document.querySelector('meta[name="theme-color"]')?.setAttribute('content', resolved === 'dark' ? '#100d17' : '#f7f6fa');
}
applyTheme(savedTheme());

export function ThemeSwitch() {
  const [theme, setTheme] = useState<Theme>(savedTheme);
  useEffect(() => {
    applyTheme(theme);
    const update = () => { if (theme === 'system') applyTheme(theme); };
    preference.addEventListener('change', update);
    return () => preference.removeEventListener('change', update);
  }, [theme]);
  function choose(value: Theme) {
    setTheme(value);
    try { if (value === 'system') localStorage.removeItem(key); else localStorage.setItem(key, value); } catch { /* Continue without persistence. */ }
  }
  return <div className="theme-switch" role="group" aria-label="화면 테마">
    {(['light', 'dark', 'system'] as const).map(value => <button key={value} type="button" aria-pressed={theme === value}
      onClick={() => choose(value)}>{value === 'light' ? '라이트' : value === 'dark' ? '다크' : '시스템'}</button>)}
  </div>;
}
