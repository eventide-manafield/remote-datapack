# HTTP API

[사용자 안내](../README.md) · [아키텍처](ARCHITECTURE.md) · [보안](SECURITY.md)

## 공통 계약

API는 설정한 공개 origin의 `/api/` 아래에 있습니다. 인증이 구성되지 않으면 관리 경로는 `503 management_unavailable`입니다. 공개 `GET /health`는 `200 {"status":"ok"}`를 반환하며, 로그인 가능 여부나 데이터팩 적용 성공을 뜻하지 않습니다.

- 토큰 교환을 제외한 관리 API는 세션 쿠키가 필요합니다. 변경 요청에는 정확한 `Origin`과 `X-CSRF-Token`도 필요합니다. 토큰 교환은 Origin과 토큰 본문을 요구합니다.
- GET의 Origin은 생략할 수 있지만, 제공하면 일치해야 합니다. `Sec-Fetch-Site`가 있으면 `same-origin`이어야 하며 읽기 요청은 `none`도 허용합니다. CORS를 제공하지 않습니다.
- 아래 API의 query 문자열은 거부합니다. 단일값 헤더가 여러 번 오면 `400 ambiguous_header`입니다. GET·로그아웃·삭제·적용은 본문을 받지 않습니다.
- 본문을 보내는 요청에는 명시적인 `Content-Length`가 필요합니다. 관리 API는 `Transfer-Encoding`을 거부하고, 파일·템플릿·적용 쓰기 경로는 `Content-Encoding`도 거부합니다.
- JSON 응답은 UTF-8입니다. 응답은 `Cache-Control: no-store`이며 오류에 자격 증명·서버 경로를 포함하지 않습니다. 연결 제한과 프록시 조건은 [보안 문서](SECURITY.md)를 따릅니다.

## 인증과 세션

게임의 `/rdpack` 링크는 `/#/auth/<token>`입니다. 브라우저는 fragment를 주소창에서 제거한 뒤 토큰을 JSON 본문으로 교환합니다. 토큰은 60초 동안 한 번만 사용할 수 있습니다. 새 링크 발급은 이전 미사용 링크를, 새 토큰 교환 성공은 해당 플레이어의 이전 웹 세션을 대체합니다.

| 요청 | 본문 / 헤더 | 성공 응답 |
|---|---|---|
| `POST /api/session/redeem` | `{"token":"<token>"}`, JSON ≤256바이트, Origin | 200 세션 정보 + Set-Cookie |
| `GET /api/session` | 세션 쿠키 | 200 세션 정보 |
| `POST /api/session/logout` | 세션 쿠키·Origin·CSRF, 본문 없음 | 200 `{"status":"signed_out"}` + 쿠키 만료 |
| `GET /api/session/avatar` | 세션 쿠키 | 200 `image/png`; 이미지 없음은 404 `avatar_unavailable` |

JSON Content-Type은 `application/json` 또는 `application/json; charset=utf-8`입니다. 토큰 교환은 URL-safe Base64 43자의 `token` 필드 하나만 받습니다. 전역 교환 한도는 버스트 20회, 초당 5회 충전이며 초과하면 `429`와 `Retry-After: 1`을 반환합니다.

세션 응답 필드는 `playerId`(UUID), `playerName`, `csrf`(43자), `expiresInSeconds`(절대 만료까지 남은 초), `apply`([적용 상태](#변경사항-적용))입니다. 세션 ID는 쿠키로만 전달됩니다.

| 모드 | 쿠키 이름 | 속성 |
|---|---|---|
| HTTPS 프록시 | `__Host-remotedatapack_session` | Secure, HttpOnly, SameSite=Strict, Path=/ |
| 직접 HTTP | `remotedatapack_http_<공개 origin 해시>` | HttpOnly, SameSite=Strict, Path=/ |
| 로컬 개발 | `remotedatapack_dev_session` | HttpOnly, SameSite=Strict, Path=/ |

Domain은 설정하지 않습니다. 발급 시 브라우저 세션 쿠키를 사용하고, 제거 시 `Max-Age=0`을 보냅니다. HTTP 쿠키 이름의 해시는 정규화된 공개 origin의 SHA-256 앞 8바이트를 16진수로 표기한 값입니다. 쿠키 자체에 포트 격리 기능은 없습니다.

`auth.session-ttl-minutes`와 `auth.idle-timeout-minutes`는 각각 기본 30분, 범위 1–1440분입니다. 세션 조회는 유휴 시간을 갱신해도 절대 만료를 연장하지 않습니다. 링크 수명 60초, 플레이어당 세션 1개, UI 세션 확인 5초는 설정에서도 고정값입니다. 로그아웃·재접속·권한 회수의 효과는 [접속 수명](SECURITY.md#접속-수명과-작업-취소)을 따릅니다. 토큰 교환 이외의 요청에서 401 또는 `permission_revoked` 응답은 해당 세션 쿠키를 만료시킵니다.

## 목록과 템플릿

`GET /api/packs`는 세션 쿠키가 필요합니다. 응답은 다음 구조입니다.

| 필드 | 의미 |
|---|---|
| `readOnly`, `pendingApply` | 파일 서비스 비활성 여부, 적용 대기 여부 |
| `directoryStatus` | `ok`, `missing`, `unavailable`, `unsupported_filesystem` |
| `truncated` | 검사·표시 한도로 목록이 일부인지 여부 |
| `packs[]` | 아래 팩 항목 배열 |

팩 항목은 `id`, `name`, `type`, `source`, `enabled`, `required`, `discovered`, `localMatch`, `modifiable`, `downloadable`, `deletable`, `protected`, `protectionReason`을 갖습니다. `type`은 `zip`, `directory`, `builtin`, `plugin`, `unmapped`이며 미발견 로컬 후보의 `enabled`는 `null`입니다. 조작 가능 여부는 개별 플래그와 실제 작업 시 검증으로 판단하며, `readOnly: false`만으로 모든 파일을 조작할 수 있는 것은 아닙니다.

`id`는 서버 프로세스별 난수를 포함한 64자 소문자 16진수로, 파일 경로나 Paper 원본 ID가 아닙니다. 재시작 후 다시 조회해야 합니다. 보호 사유에는 `built_in`, `plugin_provided`, `required_pack`, `directory_pack`, `unverified_location`, `not_discovered`, `read_only`가 있고 조작 가능한 ZIP은 `zip_management`입니다. `source`는 `world`, `built_in`, `feature`, `plugin`, `server`, `default`, `unknown`, `local` 중 하나입니다.

`GET /api/templates`는 현재 서버 형식을 `{"minecraft":"26.3","major":121,"minor":0}`처럼 반환합니다. `POST /api/templates`는 다음 필드만 가진 JSON(≤8 KiB)을 받아 200 `application/zip`, `Content-Disposition: attachment; filename="<namespace>.zip"`으로 응답합니다. POST에는 Origin·세션·CSRF가 필요합니다.

| 필드 | 조건 |
|---|---|
| `namespace` | 1–64자, `[a-z0-9][a-z0-9_.-]{0,63}`, `minecraft` 제외 |
| `author` | 1–80자 |
| `description` | 1–512자 |

문자열 길이는 Java UTF-16 길이 기준입니다. 공백뿐인 값, 제어·서식 문자, 잘못된 surrogate를 거부하며 앞뒤 공백은 정리합니다. 작성자는 표준 `pack.mcmeta` 필드가 아니라 ZIP 안의 `README.txt`에 기록됩니다. 대상 버전은 서버가 결정합니다. 생성 직후 접속권을 재검증하며 템플릿 다운로드 자체는 서버에 팩을 설치하지 않습니다.

## 파일 작업

모든 경로에 세션 쿠키가 필요하고, 변경에는 Origin·CSRF가 추가됩니다. `{id}`는 목록의 팩 ID입니다.

| 요청 | 추가 조건 | 성공 응답 |
|---|---|---|
| `POST /api/packs/upload` | 원본 ZIP 본문, `Content-Type: application/zip`, 정확한 Content-Length | 202 작업 정보 |
| `POST /api/packs/{id}/replace` | ZIP 본문, 같은 형식·길이 헤더, `X-Pack-Confirm` | 202 작업 정보 |
| `DELETE /api/packs/{id}` | `X-Pack-Confirm`, 본문 없음 | 202 작업 정보 |
| `GET /api/packs/{id}/download` | 본문 없음 | 200 ZIP attachment |
| `GET /api/jobs/{jobId}` | 작업을 시작한 동일 웹 세션 | 200 작업 정보 |

`X-Pack-Confirm`은 정확한 기존 파일명을 UTF-8로 URL 인코딩한 값이며 헤더 길이는 최대 2,048자입니다. 업로드는 multipart가 아닌 ZIP 바이트를 받습니다. 새 파일명은 서버가 `rd-<UUID>.zip`으로 지정합니다. 교체·삭제 대상 조건과 크기 제한은 [보안 문서](SECURITY.md#zip-검증)에 있습니다.

작업 정보는 `id`(UUID), `action`(`upload`, `replace`, `delete`, `download`), `state`, `code`, `name`입니다. 상태는 `receiving`, `queued`, `validating`, `backing_up`, `committing`, `refreshing`, `pending_apply`, `failed`, `recovery_required`, `downloaded` 중 하나입니다. 작업 종류와 조회 시점에 따라 중간 상태는 관측되지 않을 수 있습니다.

업로드의 202는 수신 완료 이후 반환하며 **검증·파일 변경 완료를 보장하지 않습니다**. 삭제도 비동기 작업입니다. `pending_apply`가 파일 변경 성공이며 게임 적용 완료는 별도입니다. 실패는 `code`를 확인합니다. 최근 128개 작업만 메모리에 보관하고 없는 작업이나 다른 세션의 작업은 `404 job_not_found`입니다. 다운로드는 직접 스트리밍하므로 작업 ID를 응답으로 제공하지 않습니다.

다운로드는 ZIP 전체 검증 후 헤더를 보내고 청크 사이에 세션 취소를 검사합니다. 전송 시작 후 실패하면 JSON 오류 대신 전송 중단으로 나타날 수 있습니다. 응답을 잃으면 목록·작업 상태·감사 로그를 확인해야 하며 변경 요청을 자동 재전송하지 않습니다.

## 변경사항 적용

`GET /api/apply`는 세션 쿠키로 상태를 조회합니다. `POST /api/packs/apply`는 세션·Origin·CSRF와 `X-Apply-Revision`(1–18자리 10진수)이 필요하며 본문은 없습니다. 확인한 revision으로 요청하고 202를 받은 뒤 상태를 조회합니다.

| 응답 필드 | 계약 |
|---|---|
| `state` | `clean`, `pending`, `applying`, `success`, `failed`, `unavailable` |
| `lastResult`, `error` | 마지막 결과(`clean`, `success`, `failed`)와 오류 코드 |
| `pending`, `applying`, `fileBusy`, `recoveryRequired` | 대기·실행·파일 작업·복구 상태 |
| `revision`, `changes[]` | 변경 세대, `{name, action}` 목록. 집계 항목의 action은 `multiple` |
| `cooldownRemainingMs`, `attempts` | 서버 공통 남은 쿨타임과 시작한 리로드 시도 수 |
| `supported`, `activationSupported` | 현재 빌드의 적용 지원 여부, 활성 상태 변경 지원 여부(항상 false) |

쿨타임은 독립 필드이며 `state: "cooldown"`은 없습니다. UI의 ‘적용 완료’는 `clean`에 대응합니다. 202는 실행 접수이지 성공이 아니며, 최종 `lastResult`와 `pending`을 확인해야 합니다. revision 불일치는 `409 changes_changed`, 대기 없음은 `409 nothing_to_apply`, 중복·파일 작업 중은 `409 operation_busy`, 쿨타임은 `429 apply_cooldown`과 남은 초를 올림한 `Retry-After`입니다.

## 오류 처리

일반 실패 본문은 `{"error":"<code>"}`입니다. 아래는 주요 코드이며, 비동기 파일 작업의 최종 실패는 HTTP 200 작업 조회의 `state`·`code`로 전달될 수 있습니다.

| 상태 | 주요 의미 / 코드 |
|---|---|
| 400 | `invalid_body`, `invalid_template_fields`, `invalid_host`, `ambiguous_header`, `query_not_allowed` |
| 401 | `invalid_credential`, `invalid_or_expired_link`, `session_ended`, `session_expired`, `game_disconnected`, `operation_cancelled` |
| 403 | `invalid_origin`, `invalid_csrf`, `permission_revoked` |
| 404 / 405 | `job_not_found`, `avatar_unavailable` / `method_not_allowed`(Allow 헤더) |
| 408 | `upload_timeout`, `download_timeout`, `operation_timeout` |
| 409 | `operation_busy`, `confirmation_required`, `confirmation_mismatch`, `active_pack_delete_disabled`, `protected_or_missing_pack`, `unverified_location`, `filesystem_changed`, `backup_quota_exceeded`, 적용 충돌 |
| 411 / 413 / 415 | `content_length_required` / `body_too_large`, `file_too_large`, `unsupported_encoding`, `request_body_not_supported` / `json_required`, `zip_required` |
| 414 / 431 | URI 또는 요청 헤더 한도 초과 |
| 422 | `invalid_zip`, `incompatible_pack_format` |
| 429 | `too_many_attempts`, `apply_cooldown` |
| 503 | `auth_busy`, `main_thread_timeout`, `auth_unavailable`, `files_unavailable`, `packs_busy`, `packs_unavailable`, `apply_unsupported_build`, `reload_completion_unconfirmed`, `recovery_required` |

HTTP 파서·워커 포화 오류는 `request_rejected`를 사용할 수 있고, 연결 기한 초과는 응답 없이 연결 종료로 나타날 수 있습니다. 인증 불능은 새 링크·권한을 확인하고, 복구 필요 상태는 [복구 절차](SECURITY.md#백업과-복구)를 먼저 따릅니다.
