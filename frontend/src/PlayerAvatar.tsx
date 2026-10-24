import { useState } from 'react';

/** Fixed same-origin URL: no UUID/token in URLs, CDN access, or additional polling in the browser. */
export function PlayerAvatar({ name }: { name: string }) {
  const [loaded, setLoaded] = useState(false);
  const [failed, setFailed] = useState(false);
  return <span className="avatar" aria-hidden="true">
    {!loaded && name.slice(0, 1).toUpperCase()}
    {!failed && <img className={loaded ? 'avatar-face' : 'avatar-face avatar-loading'}
      src="/api/session/avatar" alt="" width="36" height="36" referrerPolicy="no-referrer"
      onLoad={() => setLoaded(true)} onError={() => { setLoaded(false); setFailed(true); }} />}
  </span>;
}
