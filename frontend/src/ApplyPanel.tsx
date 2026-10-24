import { useEffect, useState } from 'react';
export type ApplyState = {
  state: string; lastResult: string; error: string; pending: boolean; applying: boolean; fileBusy: boolean;
  revision: number; cooldownRemainingMs: number; supported: boolean; recoveryRequired: boolean;
  changes: { name: string; action: string }[];
};
const failures: Record<string,string> = {
  reload_completion_unconfirmed: '리로드 완료를 확인하지 못했습니다. 서버 로그를 확인한 후 쿨타임이 끝나면 재시도할 수 있습니다.',
  reload_failed: '데이터 리로드에 실패했습니다. 서버 로그를 확인해 주세요.',
  changes_changed: '확인 중 변경사항이 추가되었습니다. 목록을 다시 확인해 주세요.',
  apply_cooldown: '서버 공통 쿨타임 중입니다. 잠시 후 다시 시도해 주세요.',
  operation_busy: '다른 파일 작업 또는 적용 작업이 진행 중입니다.',
  nothing_to_apply: '적용할 변경사항이 없습니다.',
  permission_revoked: '권한이 회수되어 적용을 시작하지 않았습니다.',
  operation_cancelled: '세션이 종료되어 적용을 시작하지 않았습니다.',
  apply_schedule_failed: '적용 작업을 시작하지 못했습니다. 잠시 후 다시 시도해 주세요.',
};
export function ApplyPanel({state,onState,csrf,onSessionEnded,busy}: {
  state?: ApplyState; onState:(value:ApplyState)=>void; csrf:string; onSessionEnded:()=>void; busy:boolean;
}) {
  const [preview,setPreview]=useState<ApplyState|null>(null), [message,setMessage]=useState(''), [requesting,setRequesting]=useState(false);
  const [remaining,setRemaining]=useState(0);
  useEffect(()=>{
    const until=performance.now()+(state?.cooldownRemainingMs??0);
    const update=()=>setRemaining(Math.max(0,Math.ceil((until-performance.now())/1000)));
    update(); if(!state?.cooldownRemainingMs)return;
    const timer=window.setInterval(update,500);return()=>window.clearInterval(timer);
  },[state]);
  async function fetchState(confirm=false) {
    setRequesting(true);setMessage('');
    try {
      const response=await fetch(confirm?'/api/packs/apply':'/api/apply',{
        method:confirm?'POST':'GET',credentials:'same-origin',cache:'no-store',signal:AbortSignal.timeout(3500),
        headers:confirm?{'X-CSRF-Token':csrf,'X-Apply-Revision':String(preview!.revision)}:{},
      });
      const data=await response.json();
      if(response.status===401 || response.status===403){onSessionEnded();return;}
      if(!response.ok){setPreview(null);throw new Error(failures[data.error]??'적용 상태를 확인하지 못했습니다. 새로 확인해 주세요.');}
      onState(data);setPreview(confirm?null:data);
    }catch(error){setMessage(error instanceof Error?error.message:'연결을 확인해 주세요.');}
    finally{setRequesting(false);}
  }
  if(!state)return null;
  const disabled=busy || requesting || state.fileBusy || state.applying || !state.pending || !state.supported || state.recoveryRequired || remaining>0;
  const label=state.state==='applying'?'적용 중':state.state==='failed'?'적용 실패':state.pending?'적용 대기':state.state==='success'?'적용 성공':'적용 완료';
  const actions:Record<string,string>={upload:'추가',replace:'교체',delete:'삭제',multiple:'여러 변경'};
  return <section className="panel apply-panel" aria-label="변경사항 적용">
    <div className="apply-row"><div>
      <div className="apply-heading"><h2>변경사항 적용</h2><span className={`badge ${state.pending ? 'badge-warm' : ''}`} role="status">{label}{remaining>0?` · 쿨타임 ${remaining}초`:''}</span></div>
      {!state.pending && !state.applying && <p className="apply-description">반영 대기 중인 변경사항이 없습니다.</p>}
      {state.pending && !state.applying && <p className="apply-description">{state.changes.length}개 변경 항목을 확인하고 서버에 반영하세요.</p>}
      {state.applying && <p className="apply-description">서버 데이터를 반영하고 있습니다. 잠시 기다려 주세요.</p>}
    </div><button className="primary" disabled={disabled} onClick={()=>{void fetchState();}}>변경사항 적용 <span aria-hidden="true">↗</span></button></div>
    {state.state==='failed' && <p role="alert">{failures[state.error]??'적용을 완료하지 못했습니다. 대기 항목은 유지됩니다. 서버 로그를 확인해 주세요.'}</p>}
    {!state.supported && <p role="alert">이 서버 버전은 완료 확인이 검증되지 않아 웹 적용을 사용할 수 없습니다.</p>}
    {state.recoveryRequired && <p role="alert">파일 복구가 필요하여 적용이 차단되었습니다. 서버 관리자에게 문의해 주세요.</p>}
    <details className="help"><summary>적용 전 알아두기</summary><p>파일 변경은 자동 적용되지 않습니다. 이 화면에서 변경한 파일을 한 번의 데이터 리로드로 반영합니다.<br />새 팩이 활성화될 수 있으며 서버가 잠시 지연될 수 있습니다.<br />이미 시작된 적용은 로그아웃해도 계속됩니다.</p></details>
    {preview && <div className="confirm-box" role="dialog" aria-label="변경사항 적용 확인">
      <h3>서버 전체에 반영할 변경사항</h3>
      <ul>{preview.changes.map(change=><li key={change.name}>{change.name} · {actions[change.action]??'변경'}</li>)}</ul>
      <p>모든 관리자가 공유하는 적용 작업입니다. 새 팩이 활성화될 수 있고 서버가 잠시 지연될 수 있습니다. 이미 시작된 적용은 로그아웃해도 계속됩니다. 개별 함수·레시피의 정상 동작까지 보장하는 검사는 아닙니다.</p>
      <button className="logout" disabled={disabled || preview.revision!==state.revision} onClick={()=>{void fetchState(true);}}>확인 후 적용</button>
      <button className="logout" disabled={requesting} onClick={()=>setPreview(null)}>취소</button>
    </div>}
    {message && <p role="alert">{message}</p>}
  </section>;
}
