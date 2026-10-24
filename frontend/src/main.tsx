import { useCallback, useEffect, useRef, useState } from 'react';
import { createRoot } from 'react-dom/client';
import './style.css';
import { PlayerAvatar } from './PlayerAvatar';
import { ThemeSwitch } from './ThemeSwitch';
import { PackList } from './PackList';
import type { ApplyState } from './ApplyPanel';

const configuredName = document.querySelector<HTMLMetaElement>('meta[name="remote-datapack-server"]')?.content;
const serverName = configuredName && configuredName !== '__REMOTE_DATAPACK_SERVER_NAME__' ? configuredName : 'Minecraft Server';
document.title = `Remote Datapack · ${serverName}`;

type Session = { playerId: string; playerName: string; csrf: string; expiresInSeconds: number; apply?: ApplyState };
type State = 'waiting' | 'authenticating' | 'authenticated' | 'ended' | 'failed' | 'unavailable';
// Opening another login link in this tab starts a fresh authentication flow.
window.addEventListener('hashchange', () => window.location.reload());
// Fragments never reach HTTP. Remove immediately; keep the one-use secret in memory only.
let initialToken: string | null = /^#\/auth\/([A-Za-z0-9_-]{43})$/.exec(window.location.hash)?.[1] ?? null;
const invalidFragment = !!window.location.hash && !initialToken;
if (window.location.hash) window.history.replaceState(null, '', window.location.pathname);

function App() {
  const [state, setState] = useState<State>(initialToken ? 'authenticating' : 'waiting');
  const [session, setSession] = useState<Session | null>(null);
  const [busy, setBusy] = useState(false);
  const [apply, setApply] = useState<ApplyState | undefined>();
  const stopped = useRef(false);
  useEffect(() => {
    let disposed = false;
    let authenticated = false;
    let timer: number | undefined;
    const controller = new AbortController();
    async function load(token?: string) {
      try {
        const response = await fetch(token ? '/api/session/redeem' : '/api/session', {
          method: token ? 'POST' : 'GET',
          headers: token ? { 'Content-Type': 'application/json' } : {},
          body: token ? JSON.stringify({ token }) : undefined,
          cache: 'no-store', credentials: 'same-origin',
          signal: AbortSignal.any([controller.signal, AbortSignal.timeout(3000)]),
        });
        if (disposed || stopped.current) return;
        if (!response.ok) {
          setSession(null);
          setState(response.status === 503 ? 'unavailable' : token ? 'failed' : !authenticated && response.status === 401 ? 'waiting' : 'ended');
          if (authenticated && response.status === 503) timer = window.setTimeout(() => { void load(); }, 5000);
          return;
        }
        const result: Session = await response.json();
        if (disposed || stopped.current) return;
        authenticated = true; setSession(result); setApply(result.apply); setState('authenticated');
        timer = window.setTimeout(() => { void load(); }, 5000);
      } catch {
        if (!disposed && !stopped.current) { setSession(null); setState(authenticated ? 'unavailable' : 'failed');
          if (authenticated) timer = window.setTimeout(() => { void load(); }, 5000); }
      }
    }
    const token = initialToken; initialToken = null;
    if (invalidFragment) setState('failed');
    else void load(token ?? undefined);
    return () => { disposed = true; controller.abort(); window.clearTimeout(timer); };
  }, []);
  const endSession = useCallback(() => {
    stopped.current = true; setSession(null); setState('ended');
  }, []);
  async function logout() {
    if (!session || busy) return;
    setBusy(true);
    stopped.current = true; // A late poll must never reopen a locally closed session.
    try {
      const response = await fetch('/api/session/logout', {
        method: 'POST', credentials: 'same-origin', cache: 'no-store',
        headers: { 'X-CSRF-Token': session.csrf }, signal: AbortSignal.timeout(3000),
      });
      setState(response.ok || response.status === 401 ? 'ended' : 'failed');
    } catch { setState('failed'); }
    finally { setSession(null); setBusy(false); }
  }
  const labels: Record<State, string> = {
    waiting: '인증 대기', authenticating: '게임 접속과 권한 확인 중…', authenticated: '인증 성공',
    ended: '세션 종료', failed: '인증 실패', unavailable: '인증을 사용할 수 없습니다',
  };
  const descriptions: Record<State, string> = {
    waiting: '게임에서 /remotedatapack 또는 /rdpack을 입력하고 본인에게 표시된 링크를 열어 주세요.',
    authenticating: '일회용 접속 링크를 확인하고 있습니다.',
    authenticated: '게임을 나가거나 관리 권한이 회수되면 이 웹 세션도 종료됩니다.',
    ended: '로그아웃했거나 접속권이 만료·회수되었습니다. 게임에서 새 링크를 발급받아 주세요.',
    failed: '링크가 만료되었거나 이미 사용됐을 수 있습니다. 연결 상태를 확인하고 게임에서 새 링크를 발급받아 주세요.',
    unavailable: '서버가 응답하지 않습니다. 잠시 후 다시 확인해 주세요.',
  };
  const headings: Partial<Record<State, string>> = { waiting: '게임에서 시작해 주세요', authenticating: '접속을 확인하고 있어요', ended: '세션이 종료되었습니다', failed: '인증하지 못했습니다', unavailable: '서버에 연결할 수 없습니다' };
  return <main>
    <header className="topbar">
      <div className="brand"><span className="mark" aria-hidden="true">RD</span><span>REMOTE DATAPACK</span></div>
      <ThemeSwitch />
    </header>
    <section className="intro" aria-label="서버"><p className="eyebrow">{serverName}</p></section>
    {state === 'authenticated' ? <section className="session-bar" aria-label="게임 연결 상태">
      <div className="session-identity"><PlayerAvatar key={session!.playerId} name={session!.playerName} />
        <div><strong>{session?.playerName}</strong><p className="status" role="status"><span className="dot ready" aria-hidden="true" />{labels[state]}<span className="session-hint"> · 게임에 연결됨</span></p></div>
      </div>
      <button className="quiet" onClick={() => { void logout(); }} disabled={busy}>{busy ? '종료 중…' : '웹 세션 종료'}</button>
    </section> : <section className="panel auth-panel" aria-labelledby="panel-title">
      <span className="auth-symbol" aria-hidden="true">RD</span>
      <p className="eyebrow">IN-GAME ACCESS</p>
      <h2 id="panel-title">{headings[state] ?? labels[state]}</h2>
      <p className="status" role="status" aria-live="polite"><span className={`dot ${state === 'failed' || state === 'ended' ? 'offline' : 'checking'}`} aria-hidden="true" />{labels[state]}</p>
      <p className="auth-description">{descriptions[state]}</p>
      {state === 'waiting' && <code className="command">/rdpack</code>}
      <p className="footnote">게임에 접속한 관리자만 사용할 수 있습니다. 게임을 나가면 웹 세션도 종료됩니다.</p>
    </section>}
    {state === 'authenticated' && !busy && <PackList onSessionEnded={endSession} csrf={session!.csrf} apply={apply} onApplyState={setApply} />}
    <footer><span><strong>manafield</strong> / Remote Datapack</span><span>게임 접속 종료 시 웹 세션도 종료됩니다.</span></footer>
  </main>;
}
createRoot(document.getElementById('root')!).render(<App />);
