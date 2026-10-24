import { useEffect, useRef, useState } from 'react';
import { ApplyPanel, type ApplyState } from './ApplyPanel';

type Pack = {
  id: string; name: string; type: string; source: string; enabled: boolean | null;
  required: boolean; discovered: boolean; localMatch: boolean; modifiable: boolean;
  downloadable?: boolean; deletable?: boolean; protected: boolean; protectionReason: string;
};
type Listing = { readOnly: boolean; pendingApply?: boolean; directoryStatus: string; truncated: boolean; packs: Pack[] };
type Job = { id: string; action: string; state: string; code: string; name: string };
const types: Record<string, string> = { zip: 'ZIP', directory: '폴더', builtin: '내장', plugin: '플러그인', unmapped: '확인 불가' };
const reasons: Record<string, string> = {
  built_in: '보호됨 · 내장팩', plugin_provided: '보호됨 · 플러그인 제공', required_pack: '보호됨 · 필수 팩',
  directory_pack: '보호됨 · 폴더 팩', unverified_location: '보호됨 · 위치 확인 불가',
  not_discovered: '보호됨 · 서버 미인식', read_only: '읽기 전용',
};
const errors: Record<string, string> = {
  invalid_zip: '안전하게 검증할 수 없는 ZIP입니다. 루트 pack.mcmeta, 경로, 압축 크기와 구조를 확인해 주세요.',
  incompatible_pack_format: 'Minecraft 26.3 데이터팩 형식(121.0)을 지원하지 않습니다.',
  operation_busy: '다른 파일 작업이 진행 중입니다. 완료 후 다시 시도해 주세요.',
  backup_quota_exceeded: '백업 공간이 부족합니다. 서버 관리자에게 백업 정리를 요청해 주세요.',
  confirmation_mismatch: '확인한 파일명이 현재 대상과 일치하지 않습니다.',
  active_pack_delete_disabled: '활성 팩은 삭제할 수 없습니다. 웹 활성화 변경은 현재 제한됩니다.',
  refresh_failed_rolled_back: '발견 목록 갱신에 실패하여 파일 변경을 복원했습니다. 서버 로그를 확인해 주세요.',
  restore_failed: '파일 복원에 실패했습니다. 추가 작업을 멈추고 서버 관리자에게 백업 복원을 요청해 주세요.',
  file_too_large: '파일이 서버의 업로드 용량 한도를 넘었습니다.',
};
export function PackList({ onSessionEnded, csrf, apply, onApplyState }: { onSessionEnded: () => void; csrf: string; apply?: ApplyState; onApplyState: (state: ApplyState) => void }) {
  const [revision, setRevision] = useState(0);
  const [listing, setListing] = useState<Listing | null>(null);
  const [loading, setLoading] = useState(true);
  const [failed, setFailed] = useState(false);
  const [working, setBusy] = useState(false);
  const busy = working || !!apply?.applying || !!apply?.recoveryRequired;
  const [message, setMessage] = useState('');
  const [file, setFile] = useState<File | null>(null);
  const [dialog, setDialog] = useState<{ pack: Pack; action: 'replace' | 'delete' } | null>(null);
  const [confirmation, setConfirmation] = useState('');
  const lifetime = useRef(new AbortController());
  useEffect(() => () => lifetime.current.abort(), []);
  useEffect(() => {
    const controller = new AbortController();
    let disposed = false;
    setLoading(true); setFailed(false); setListing(null);
    void (async () => {
      try {
        const response = await fetch('/api/packs', {
          credentials: 'same-origin', cache: 'no-store',
          signal: AbortSignal.any([controller.signal, AbortSignal.timeout(6000)]),
        });
        if (disposed) return;
        if (response.status === 401 || response.status === 403) { onSessionEnded(); return; }
        if (!response.ok) throw new Error('listing_unavailable');
        const data: Listing = await response.json();
        if (!disposed) setListing(data);
      } catch { if (!disposed) setFailed(true); }
      finally { if (!disposed) setLoading(false); }
    })();
    return () => { disposed = true; controller.abort(); };
  }, [revision, onSessionEnded]);
  async function request(path: string, options: RequestInit = {}) {
    const response = await fetch(path, { ...options, credentials: 'same-origin', cache: 'no-store',
      signal: AbortSignal.any([lifetime.current.signal, AbortSignal.timeout(9500)]),
    });
    if (response.status === 401 || response.status === 403) { onSessionEnded(); throw new Error('session_ended'); }
    if (!response.ok) { const data = await response.json(); throw new Error(errors[data.error] ?? '요청을 처리하지 못했습니다. 목록을 다시 확인해 주세요.'); }
    return response;
  }
  async function mutate(action: 'upload' | 'replace' | 'delete', pack?: Pack) {
    if (busy || (action !== 'delete' && !file) || (pack && confirmation !== pack.name)) return;
    setBusy(true); setMessage('작업 중 · 파일 수신 및 검증');
    try {
      const path = action === 'upload' ? '/api/packs/upload' : `/api/packs/${pack!.id}${action === 'replace' ? '/replace' : ''}`;
      let job: Job = await (await request(path, {
        method: action === 'delete' ? 'DELETE' : 'POST',
        headers: { 'X-CSRF-Token': csrf, ...(pack ? { 'X-Pack-Confirm': encodeURIComponent(confirmation) } : {}),
          ...(action !== 'delete' ? { 'Content-Type': 'application/zip' } : {}) },
        body: action === 'delete' ? undefined : file!,
      })).json();
      setDialog(null); setFile(null); setConfirmation('');
      const until = Date.now() + 35000;
      while (!['pending_apply', 'failed', 'recovery_required'].includes(job.state)) {
        setMessage('작업 중 · 검증, 백업 및 파일 반영');
        if (Date.now() > until) throw new Error('작업 결과 확인이 지연됩니다. 목록과 서버 감사 로그를 확인한 후 다시 시도해 주세요.');
        await new Promise(resolve => window.setTimeout(resolve, 750));
        job = await (await request(`/api/jobs/${job.id}`)).json();
      }
      if (job.state !== 'pending_apply') throw new Error(errors[job.code] ?? '파일 작업에 실패했습니다. 서버 감사 로그를 확인해 주세요.');
      setMessage(`적용 대기 · ${job.name} 파일 변경 완료. 게임 데이터는 아직 리로드하지 않았습니다.${job.code ? ' 백업 보관 상태를 관리자에게 확인해 주세요.' : ''}`);
    } catch (error) {
      if (!lifetime.current.signal.aborted) setMessage(error instanceof Error ? error.message : '작업 결과를 확인하지 못했습니다.');
    } finally { if (!lifetime.current.signal.aborted) { setBusy(false); setRevision(value => value + 1); if (apply) void request("/api/apply").then(r => r.json()).then(onApplyState).catch(() => {}); } }
  }
  async function download(pack: Pack) {
    if (busy) return; setBusy(true); setMessage('작업 중 · 다운로드 검증');
    try {
      const blob = await (await request(`/api/packs/${pack.id}/download`)).blob();
      if (lifetime.current.signal.aborted) return;
      const url = URL.createObjectURL(blob); const anchor = document.createElement('a');
      anchor.href = url; anchor.download = pack.name; anchor.click(); window.setTimeout(() => URL.revokeObjectURL(url), 1000);
      setMessage('다운로드 완료');
    } catch (error) { if (!lifetime.current.signal.aborted) setMessage(error instanceof Error ? error.message : '다운로드 실패'); }
    finally { if (!lifetime.current.signal.aborted) setBusy(false); }
  }
  return <><ApplyPanel state={apply} onState={onApplyState} csrf={csrf} onSessionEnded={onSessionEnded} busy={working} />
  <section className="packs panel" aria-labelledby="packs-title">
    <div className="panel-heading"><div className="section-title"><h2 id="packs-title">데이터팩 목록</h2>{listing && <span className="count">{listing.packs.length}</span>}</div>
      <button className="logout" disabled={loading || busy} onClick={() => setRevision(value => value + 1)}>목록 다시 조회</button></div>
    <p className="footnote">ZIP을 추가하거나 교체한 뒤, 변경사항 적용으로 서버에 반영하세요. 활성화·비활성화 변경은 인게임 기본 명령어로 해주세요.</p>
    {message && <p role="status" className="pack-warning">{message}</p>}
    {loading && <p role="status">목록을 불러오는 중…</p>}
    {failed && <p role="alert">목록을 불러오지 못했습니다. 잠시 후 다시 조회해 주세요.</p>}
    {listing && <>
      {(apply ? apply.pending : listing.pendingApply) && <p className="pack-warning">적용 대기 · 파일 변경 후 게임 데이터 리로드가 필요합니다.</p>}
      {listing.directoryStatus !== 'ok' && <p className="pack-warning" role="alert">로컬 데이터팩 폴더를 안전하게 확인하지 못했습니다. 서버가 인식한 팩만 표시하며 파일 연결은 보호됩니다.</p>}
      {listing.truncated && <p className="pack-warning" role="alert">조회 한도를 넘어 목록 일부만 표시합니다.</p>}
      {!listing.readOnly && listing.directoryStatus === 'ok' && !dialog && <div className="file-actions">
        <label>새 ZIP 선택 <input type="file" accept=".zip,application/zip" disabled={busy} onChange={event => setFile(event.target.files?.[0] ?? null)} /></label>
        <button className="primary" disabled={busy || !file} onClick={() => { void mutate('upload'); }}>ZIP 추가</button>
        <p className="footnote">최대 20 MiB(서버 설정에 따라 더 작을 수 있음). 안전한 새 파일명으로 저장하며 기존 팩을 덮어쓰지 않습니다.</p>
      </div>}
      {dialog && <div className="confirm-box" role="dialog" aria-modal="false" aria-label={dialog.action === 'delete' ? '삭제 확인' : '교체 확인'}>
        <h3>{dialog.action === 'delete' ? 'ZIP 삭제' : 'ZIP 교체'}: {dialog.pack.name}</h3>
        <p>백업 후 파일을 {dialog.action === 'delete' ? '삭제' : '교체'}합니다. 게임 데이터는 자동으로 리로드되지 않습니다.</p>
        {dialog.pack.enabled && <p>현재 활성 팩입니다. 교체된 내용은 이후 게임 적용 시 반영됩니다.</p>}
        {dialog.action === 'replace' && <label>교체할 ZIP <input type="file" accept=".zip,application/zip" disabled={busy} onChange={event => setFile(event.target.files?.[0] ?? null)} /></label>}
        <label>확인할 파일명 <input value={confirmation} disabled={busy} onChange={event => setConfirmation(event.target.value)} autoComplete="off" /></label>
        <button className="logout" disabled={busy || confirmation !== dialog.pack.name || (dialog.action === 'replace' && !file)} onClick={() => { void mutate(dialog.action, dialog.pack); }}>확인 후 {dialog.action === 'delete' ? '삭제' : '교체'}</button>
        <button className="logout" disabled={busy} onClick={() => { setDialog(null); setFile(null); setConfirmation(''); }}>취소</button>
      </div>}
      {listing.packs.length === 0 ? <p>표시할 데이터팩이 없습니다.</p> : <div className="pack-table"><table>
        <caption className="sr-only">데이터팩 종류, 활성 상태 및 보호 여부</caption>
        <thead><tr><th scope="col">이름</th><th scope="col">종류</th><th scope="col">활성 상태</th><th scope="col">관리</th></tr></thead>
        <tbody>{listing.packs.map(pack => <tr key={pack.id}>
          <th scope="row">{pack.name}{pack.required && <span className="pack-detail">필수</span>}</th>
          <td><span className="type-label">{types[pack.type] ?? '확인 불가'}</span></td>
          <td><span className={`pack-state ${pack.discovered && pack.enabled ? 'is-active' : ''}`}>{!pack.discovered ? '서버 미인식' : pack.enabled === true ? '활성' : pack.enabled === false ? '비활성' : '확인 불가'}</span></td>
          <td>{pack.modifiable ? <>
            <button className="logout" disabled={busy || !!dialog} onClick={() => { void download(pack); }}>다운로드</button>
            <button className="logout" disabled={busy || !!dialog} onClick={() => { setDialog({pack, action: 'replace'}); setFile(null); setConfirmation(''); }}>교체</button>
            <button className="logout danger" disabled={busy || !!dialog || !pack.deletable} onClick={() => { setDialog({pack, action: 'delete'}); setFile(null); setConfirmation(''); }}>삭제</button>
            {pack.enabled && <span className="pack-detail">활성 팩 삭제 불가</span>}
          </> : reasons[pack.protectionReason] ?? '보호됨'}</td>
        </tr>)}</tbody>
      </table></div>}
      <p className="footnote">활성 상태는 서버가 마지막으로 인식한 정보를 기준으로 합니다. 서버 미인식 항목은 파일 후보이며 유효한 데이터팩인지 아직 확인되지 않았습니다.</p>
    </>}
  </section></>;
}
