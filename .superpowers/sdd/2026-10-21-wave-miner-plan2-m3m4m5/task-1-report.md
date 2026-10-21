# Task 1 Report: 设置存储 app_setting + GET/PUT /api/settings（脱敏）

## Status: DONE_WITH_CONCERNS (minor, resolved deviations — see below)

## What was done

Followed the brief's TDD order (red → green per unit, then full suite → commit):

1. **schema.sql**: appended `app_setting(key TEXT PRIMARY KEY, value TEXT NOT NULL)` verbatim (idempotent `CREATE TABLE IF NOT EXISTS`, consistent with existing tables).
2. **SettingServiceTest**: written first verbatim from brief → run failed to compile (`找不到符号 SettingService`) = RED.
3. **SettingService**: implemented per brief, with one compile fix (see Deviations). → 2/2 pass.
4. **SettingApiTest**: written first verbatim from brief except one import adaptation (see Deviations) → run: 3/3 FAIL with 404 (`/api/settings` no controller) = genuine RED.
5. **SettingController**: implemented verbatim from brief (KNOWN_KEYS map with env fallback order `WAVEMINER_MINIMAX_API_KEY` / `WAVEMINER_ALIYUN_API_KEY`+`DASHSCOPE_API_KEY`, `SettingView` record, mask rule >10 chars → 前2+`****`+后4 else `****`, unknown key → `IllegalArgumentException` → 400 with 中文 via existing `ApiExceptionHandler`). → 3/3 pass.
6. **WebConfig**: CORS `allowedMethods` now `("GET", "POST", "PUT", "DELETE")`.
7. Full suite green → commit.

## Test evidence

| Command | Result |
|---|---|
| `./mvnw -q test -Dtest=SettingServiceTest` (impl missing) | compile failure (RED) |
| same, after SettingService impl | `Tests run: 2, Failures: 0, Errors: 0` |
| `./mvnw -q test -Dtest=SettingApiTest` (controller missing) | `Tests run: 3, Failures: 3, Errors: 0` (404s, RED) |
| same, after SettingController impl | `Tests run: 3, Failures: 0, Errors: 0` |
| **Full suite** `cd backend && ./mvnw -q test` | **BUILD SUCCESS — 73 tests, 0 failures, 0 errors, 0 skipped** (13 test classes; per-class breakdown in surefire reports all zero-failure) |

Commit: `daa7e76` `feat: 设置存储与脱敏的设置 API` (6 files, +215/−1)

## Deviations from brief (minimal, recorded)

1. **`SettingApiTest` import**: brief used `org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc`, which does not exist in Spring Boot 4.1.1. Adapted to `org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc`, matching the existing `TaskApiTest.java:14` convention. All other test code verbatim.
2. **`SettingService.getAll()` lambda ambiguity**: brief's `jdbc.query(sql, rs -> out.put(rs.getString(1), rs.getString(2)))` did not compile — `Map.put` returns a value, making the lambda both value- and void-compatible, ambiguous between `ResultSetExtractor` and `RowCallbackHandler` (javac: 对query的引用不明确). Fixed minimally with a block-body lambda (`rs -> { out.put(...); }`) which is void-compatible only → resolves to `RowCallbackHandler`, same intended behavior.

No conflicts with global constraints: no Lombok, user-facing errors 中文 ("未知的设置项: ..."), tests are hermetic (temp-dir SQLite, no network/external tools).

## Self-review findings / concerns

- **Resolved by design**: brief's two code blocks each needed a mechanical fix (above); both verified against actual compiler/framework behavior rather than guessed.
- **Env-var sensitivity (theoretical)**: `GET /api/settings` falls back to `System.getenv` for unknown keys' `configured` flag; if a developer machine/exported CI env had `WAVEMINER_MINIMAX_API_KEY` etc., GET assertions on minimax could differ — but the test always PUTs first so the stored value takes precedence, and `DASHSCOPE_API_KEY` only affects the aliyun row which the test never asserts negatively. No flakiness observed; noted for awareness.
- **`put()` `configured` flag ignores env fallback** (returns `effective != null` from DB only), whereas `list()` includes env fallback. This is exactly the brief's specified behavior (sequence: 设置表值 → 环境变量), and matches the test expectations; flagging only so later tasks relying on the 解析顺序 don't assume `put()` reflects env.
- `@Transactional` on `SettingService.set` relies on the auto-configured transaction manager (spring-boot-starter-jdbc, HikariCP pool size 1) — consistent with `TaskRepository` usage.
- CORS PUT change affects only `/api/**` cross-origin from the Vite dev origins; MockMvc tests bypass CORS so no test covers it (consistent with existing coverage approach).

## Files changed

- `backend/src/main/resources/schema.sql` (modified)
- `backend/src/main/java/com/waveminer/waveminer/config/WebConfig.java` (modified)
- `backend/src/main/java/com/waveminer/waveminer/store/SettingService.java` (new)
- `backend/src/main/java/com/waveminer/waveminer/api/SettingController.java` (new)
- `backend/src/test/java/com/waveminer/waveminer/store/SettingServiceTest.java` (new)
- `backend/src/test/java/com/waveminer/waveminer/api/SettingApiTest.java` (new)
