# 개발 안내

[사용자 안내](../README.md) · [아키텍처](ARCHITECTURE.md) · [API](API.md) · [보안](SECURITY.md)

## 환경과 빌드

- JDK 21: Java toolchain과 바이트코드 대상 버전
- Node.js 24.22.1 / npm 11.19.1: 프런트엔드 빌드용
- Gradle 9.8.2: 체크섬이 고정된 저장소 Wrapper 사용

```bash
./gradlew build
```

Windows에서는 `gradlew.bat build`를 사용합니다. 빌드는 `npm ci`, TypeScript 검사·Vite 빌드, Java 컴파일, 자산·라이선스 포함과 Shadow JAR 패키징을 수행합니다. 결과는 `build/libs/remote-datapack-1.3.1.jar`입니다. 실행 환경에는 Node/npm/Gradle이 필요하지 않습니다.

[Gradle 설정](../build.gradle.kts)의 `compileOnly` Paper API는 `1.21.9-R0.1-SNAPSHOT`입니다. API JAR와 필요한 컴파일 전용 타입을 명시하고 Paper/Minecraft 클래스는 배포에 포함하지 않습니다. Jetty·SLF4J는 전용 패키지로 옮겨 같은 JAR에 포함합니다.

[의존성 잠금](../gradle.lockfile), [SHA-256 검증 메타데이터](../gradle/verification-metadata.xml), [npm 잠금](../frontend/package-lock.json)으로 빌드 입력을 고정합니다. Paper SNAPSHOT도 체크섬이 일치해야 하므로 내용 변경 시 검증 실패를 먼저 조사하세요. 포함 의존성을 갱신할 때는 [외부 고지](../THIRD_PARTY_NOTICES.md)와 [라이선스 목록](../licenses/dependencies.json)을 함께 확인합니다.

## 버전 호환성

지원 Minecraft 버전·Java 요구사항·웹 적용 허용 빌드는 [README](../README.md#지원-버전)를 참조하세요. 버전 판정과 데이터팩 형식의 코드 기준은 [PackFormat](../src/main/java/studio/manafield/remotedatapack/datapack/PackFormat.java)입니다.

| Minecraft | 데이터팩 형식 |
|---|---|
| 1.21.9, 1.21.10 | 88.0 |
| 1.21.11 | 94.1 |
| 26.1.1, 26.1.2 | 101.1 |
| 26.2 | 107.1 |
| 26.3 | 121.0 |

형식은 공식 Paper 배포 JAR에 포함된 Mojang `version.json`의 `pack_version`을 기준으로 합니다. 새 버전을 추가할 때는 형식·공개 API·실제 리로드 완료 계약을 각각 확인해야 합니다. 미등록 버전은 시작 단계에서 비활성화되고, 알려진 버전의 미등록 Paper 빌드는 웹 적용만 제한됩니다. 호환 경계는 `PaperLevelDirectory`와 `PaperReload`에 모여 있습니다.

## 로컬 실행

별도의 Paper 테스트 서버에 빌드한 JAR를 넣습니다. 실제 서버용 설정은 [config.yml](../src/main/resources/config.yml), 명령어·권한은 [plugin.yml](../src/main/resources/plugin.yml)을 참조하세요. 공개 HTTP와 HTTPS 프록시 설정은 [사용자 안내](../README.md#설치-및-초기-설정)와 [보안 문서](SECURITY.md#https-프록시-설정)에 있습니다.

루프백 HTTP 개발 모드는 다음 조합으로만 활성화합니다.

```yaml
web:
  mode: https-proxy
  bind: 127.0.0.1
  port: 13532
  public-url: ""
  local-development: true
```

개발 모드는 실제 바인딩 주소·포트로 인증 origin을 만들며 전용 HTTP 쿠키를 사용합니다. 공개 URL과 함께 설정하거나 외부에 노출하지 마세요. 게임 접속·권한·신원 검사는 개발 모드에서도 적용됩니다. UI 제목은 `ui.server-name`으로 지정할 수 있고, 세션 확인 주기는 5초 고정입니다.

## 검증 범위

저장소에는 자동 테스트 소스가 없으므로 `./gradlew build` 성공만으로 런타임 검증을 대신할 수 없습니다. 변경 범위에 맞게 별도 테스트 환경에서 다음을 확인하고, 실제 실행한 조건을 변경 설명에 기록합니다.

| 변경 영역 | 확인할 동작 |
|---|---|
| 웹서버·패키징 | JVM HTTP 옵션 없이 시작, 선행 JDK 웹서버와 공존, 헤더·연결·시간·대기열 한도, 종료 후 포트·스레드 정리 |
| 인증·API | 동시 토큰 교환·재사용, 쿠키·Origin·CSRF, 로그아웃·재접속·권한 회수·만료와 요청 경합 |
| 파일 관리 | 경로·링크 보호, 잘못된 ZIP, 백업 한도, 원자적 변경·복구 실패, 스트리밍 중 세션 종료 |
| 적용 | 파일 변경의 리로드 분리, revision 충돌, 중복 요청, 공통 쿨타임, 실패 후 대기 상태 보존 |
| 호환성·UI | 지원 Java/Paper 조합, 실제 템플릿 형식·리로드 완료, 브라우저 인증·다운로드·테마·모바일 화면 |

실제 플레이어를 사용한 검증과 테스트용 신원 객체를 사용한 검증을 구분합니다. Paper 메인 스레드 경계와 실제 파일·리로드 동작을 확인해도 모든 게임 로직의 결과까지 보증하지는 않습니다.

## 문서와 기여

독자별 문서 역할과 작성 규칙은 [AGENTS.md](../AGENTS.md)를 따릅니다. 사용자 동작·설정이 바뀌면 README를, API나 내부 계약이 바뀌면 해당 상세 문서를 수정합니다. 변경 이력은 Git 기록과 릴리스 설명에 남깁니다. 문서만 수정할 때는 코드·설정 대조, 상대경로·앵커 검사, 중요 제약 누락 검토로 확인하고 실행 코드는 변경하지 않습니다.
