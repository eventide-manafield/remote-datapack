# Remote Datapack

[![Buy Me a Coffee](https://img.shields.io/badge/Buy_Me_a_Coffee-Support_Manafield-FFDD00?style=for-the-badge&logo=buymeacoffee&logoColor=000000)](https://buymeacoffee.com/manafield)

게임에 접속한 관리자가 웹에서 데이터팩을 관리하는 Paper 플러그인입니다.

[**최신 릴리스 다운로드**](https://github.com/eventide-manafield/remote-datapack/releases/latest)

- 데이터팩 목록 조회, ZIP 업로드·교체·다운로드·삭제
- 현재 서버 버전에 맞는 템플릿 ZIP 생성
- 변경사항을 모아 한 번에 적용
- 인게임 일회용 링크 인증, 라이트·다크 테마

## 지원 버전

| Minecraft / Paper | 필요한 Java | 웹에서 변경사항 적용이 가능한 Paper 빌드 |
|---|---|---|
| 1.21.9 | 21 이상 | 59 |
| 1.21.10 | 21 이상 | 130 |
| 1.21.11 | 21 이상 | 132 |
| 26.1.1 | 25 이상 | 29 |
| 26.1.2 | 25 이상 | 74 |
| 26.2 | 25 이상 | 134 |
| 26.3 | 25 이상 | 211, 218 |

같은 Minecraft 버전의 다른 Paper 빌드에서는 인증·파일 관리·템플릿을 사용할 수 있지만 웹 적용은 비활성화됩니다. 표에 없는 Minecraft 버전은 지원하지 않습니다. Spigot·Folia 호환은 보증하지 않습니다. 1.21.9-59와 26.1.1-29는 Paper의 ALPHA 빌드입니다.

실행에는 JAR 하나만 필요하며, 별도 JVM 옵션·Node.js·데이터베이스 설치는 필요하지 않습니다. 기본적으로 `online-mode=true` 서버에서 사용합니다. 게임 프록시를 사용하는 서버는 [신원 신뢰 설정](docs/SECURITY.md#게임-신원과-접근-권한)을 확인하세요.

## 설치 및 초기 설정

1. 릴리스 JAR를 `plugins/`에 넣고 서버를 시작합니다. 내장 웹서버의 기본 포트는 **13532**입니다.
2. 생성된 `plugins/RemoteDatapack/config.yml`에 외부 접속 주소를 입력합니다.

```yaml
web:
  mode: direct-http
  bind: 0.0.0.0
  port: 13532
  public-url: "http://mc.example.com:13532"
  local-development: false
```

3. `mc.example.com`을 서버 공인 IP나 도메인으로 바꾸고, 방화벽·공유기에서 **TCP 13532**를 허용·포트포워딩합니다. Docker에서는 컨테이너 포트도 게시합니다.
4. 서버를 정상 재시작한 뒤 게임에서 `/rdpack`을 실행합니다.

게임 포트와 웹 포트는 별개입니다. 다른 포트를 사용한다면 `port`를 바꾸고, `public-url`에는 사용자가 접속할 **외부 주소와 포트**를 적으세요. 주소가 비어 있으면 인증 링크를 발급할 수 없습니다.

**직접 HTTP는 암호화되지 않습니다.** HTTPS를 사용하려면 [프록시 설정 안내](docs/SECURITY.md#https-프록시-설정)를 따르세요. Cloudflare DNS로 직접 연결할 때는 **DNS only**를 사용합니다. 브라우저가 HTTP를 HTTPS로 바꾸는 도메인은 HTTPS 프록시나 별도 주소가 필요합니다.

업데이트는 월드와 설정을 백업한 후 서버를 정상 종료하고 기존 JAR를 교체합니다. 이전 JAR를 함께 남겨두지 마세요. 설정 변경도 정상 재시작 후 반영됩니다.

## 사용 방법과 명령어

접속 중인 플레이어에게 `remotedatapack.manage` 권한이 필요하며, 기본값은 **OP**입니다. `/remotedatapack`과 `/rdpack`은 같은 명령어입니다.

| 명령어 | 용도 |
|---|---|
| `/rdpack` | 60초 동안 한 번 사용할 수 있는 본인용 웹 링크 발급 |
| `/rdpack status` | 웹 상태와 본인의 링크·세션 수 확인. 콘솔에서는 웹 상태 확인 |
| `/rdpack revoke` | 본인의 미사용 링크와 웹 세션 모두 회수 |

웹에서 ZIP을 추가하거나 교체한 뒤 **변경사항 적용 → 확인 후 적용**을 누릅니다. 파일 교체·삭제에는 기존 파일명 확인이 필요합니다. **새 데이터팩 만들기**는 네임스페이스·작성자·설명을 입력해 템플릿을 다운로드하는 기능입니다. 내용을 작성한 뒤 ZIP으로 업로드하세요.

## 사용 시 알아둘 점

- **파일 변경만으로 게임 데이터가 적용되지는 않습니다.** 적용 시 새 팩이 활성화되거나 서버가 잠시 지연될 수 있습니다. 시작된 적용은 로그아웃해도 계속됩니다.
- 적용 종료 후 성공·실패 모두 기본 **30초의 서버 공통 쿨타임**이 있습니다. `apply.cooldown-seconds`로 1–3600초 범위에서 조정할 수 있습니다.
- 메인 월드의 안전한 ZIP 팩만 파일 관리 대상입니다. 활성 팩은 삭제할 수 없으며, 내장·플러그인·필수 팩과 폴더는 보호됩니다. 웹에서 활성화·비활성화는 제공하지 않습니다. 안전한 경로 처리·원자적 이동을 지원하지 않는 파일시스템에서는 파일 작업을 사용할 수 없습니다.
- ZIP은 기본 최대 **20 MiB**이며 루트에 `pack.mcmeta`가 있어야 합니다. 현재 서버 형식을 포함하는 `min_format`·`max_format`이 필요합니다. [ZIP 제한](docs/SECURITY.md#zip-검증)을 통과해도 팩의 게임 동작까지 보증하지는 않습니다.
- 링크를 공유하지 마세요. 게임 로그아웃·서버 재시작 시 웹 세션이 끝납니다. 권한을 회수하면 다음 인증 요청에서 접근이 차단됩니다. 세션은 기본 최대 30분이며 새 링크로 다시 인증할 수 있습니다.
- 외부 파일 편집·콘솔 리로드와 웹 작업을 겹치지 마세요. 응답을 잃었거나 복구 오류가 나면 재시도 전에 목록과 서버 로그를 확인하세요. 백업은 [수동 복구 안내](docs/SECURITY.md#백업과-복구)를 따릅니다.

## 상세 문서

- [아키텍처](docs/ARCHITECTURE.md): 구성, 스레드 경계, 파일 작업과 적용 흐름
- [API](docs/API.md): 인증·세션, 요청·응답과 오류 계약
- [보안](docs/SECURITY.md): HTTPS 설정, 접근 제어, ZIP·경로 보호, 백업
- [개발 안내](docs/DEVELOPMENT.md): 빌드 환경, 검증 방법, 기여 안내

## 라이선스

Copyright 2026 Eventide. [Apache License 2.0](LICENSE)을 따릅니다. 포함된 라이브러리의 라이선스는 [외부 라이선스 고지](THIRD_PARTY_NOTICES.md)에 정리되어 있으며 배포 JAR의 `META-INF`에도 포함됩니다.
