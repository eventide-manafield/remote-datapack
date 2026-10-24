import { useEffect, useState } from 'react';

type Format = { minecraft: string; major: number; minor: number };
export function TemplatePanel({ csrf, onSessionEnded }: { csrf: string; onSessionEnded: () => void }) {
  const [open, setOpen] = useState(false);
  const [format, setFormat] = useState<Format | null>(null);
  const [namespace, setNamespace] = useState('');
  const [author, setAuthor] = useState('');
  const [description, setDescription] = useState('');
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState('');
  const [lifetime] = useState(() => new AbortController());
  useEffect(() => () => lifetime.abort(), [lifetime]);
  useEffect(() => {
    if (!open) return;
    const controller = new AbortController();
    setFormat(null); setMessage('');
    void (async () => {
      try {
        const response = await fetch('/api/templates', { credentials: 'same-origin', cache: 'no-store',
          signal: AbortSignal.any([controller.signal, AbortSignal.timeout(5000)]) });
        if (controller.signal.aborted) return;
        if (response.status === 401 || response.status === 403) { onSessionEnded(); return; }
        if (!response.ok) throw new Error();
        const result: Format = await response.json();
        if (!controller.signal.aborted) setFormat(result);
      } catch { if (!controller.signal.aborted) setMessage('서버 버전을 확인하지 못했습니다. 닫았다가 다시 열어 주세요.'); }
    })();
    return () => controller.abort();
  }, [open, onSessionEnded]);
  const valid = /^[a-z0-9][a-z0-9_.-]{0,63}$/.test(namespace) && namespace !== 'minecraft' && !!author.trim() && !!description.trim();
  async function download() {
    if (busy || !format || !valid) return;
    setBusy(true); setMessage('');
    try {
      const response = await fetch('/api/templates', { method: 'POST', credentials: 'same-origin', cache: 'no-store',
        headers: { 'Content-Type': 'application/json', 'X-CSRF-Token': csrf },
        body: JSON.stringify({ namespace, author, description }),
        signal: AbortSignal.any([lifetime.signal, AbortSignal.timeout(6000)]) });
      if (lifetime.signal.aborted) return;
      if (response.status === 401 || response.status === 403) { onSessionEnded(); return; }
      if (!response.ok) {
        setMessage(response.status === 400 ? '입력값을 확인해 주세요. 줄바꿈과 제어 문자는 사용할 수 없습니다.' : '템플릿을 생성하지 못했습니다. 다시 시도해 주세요.'); return;
      }
      const blob = await response.blob();
      if (lifetime.signal.aborted) return;
      const url = URL.createObjectURL(blob), anchor = document.createElement('a');
      anchor.href = url; anchor.download = `${namespace}.zip`; anchor.click();
      window.setTimeout(() => URL.revokeObjectURL(url), 1000);
      setMessage('템플릿 다운로드 완료. 내용을 작성한 뒤 ZIP으로 업로드하세요.');
    } catch { if (!lifetime.signal.aborted) setMessage('다운로드하지 못했습니다. 연결 상태를 확인해 주세요.'); }
    finally { if (!lifetime.signal.aborted) setBusy(false); }
  }
  return <div className="template-tools">
    <button className="quiet" aria-expanded={open} aria-controls="template-fields" disabled={busy} onClick={() => setOpen(value => !value)}>{open ? '템플릿 닫기' : '새 데이터팩 만들기'}</button>
    {open && <div id="template-fields" className="confirm-box template-fields">
      <p>{format ? `Minecraft ${format.minecraft} · 데이터팩 형식 ${format.major}.${format.minor}` : '서버 버전 확인 중…'}</p>
      <label>네임스페이스<input value={namespace} onChange={e => setNamespace(e.target.value)} maxLength={64} placeholder="my_pack" autoCapitalize="none" autoComplete="off" spellCheck={false} disabled={busy} aria-describedby="namespace-help" /></label>
      <p id="namespace-help" className="footnote">영문 소문자·숫자로 시작하고 소문자·숫자·밑줄·마침표·하이픈을 사용할 수 있습니다. minecraft는 예약된 이름입니다.</p>
      <label>작성자<input value={author} onChange={e => setAuthor(e.target.value)} maxLength={80} disabled={busy} autoComplete="off" /></label>
      <label>설명<input value={description} onChange={e => setDescription(e.target.value)} maxLength={512} disabled={busy} autoComplete="off" /></label>
      <p className="footnote">기본 파일만 담긴 ZIP을 다운로드합니다. 서버에 추가되거나 자동 실행되지 않습니다. 작성자는 ZIP 안의 README.txt에 기록됩니다.</p>
      <button className="primary" disabled={busy || !format || !valid} onClick={() => { void download(); }}>{busy ? '생성 중…' : '템플릿 ZIP 다운로드'}</button>
      {message && <p role="status">{message}</p>}
    </div>}
  </div>;
}
