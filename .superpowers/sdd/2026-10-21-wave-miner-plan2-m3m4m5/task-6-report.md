# Task 6 Report: EngineHttpClient + MultipartBuilder + MiniMax 转写引擎

**Status:** COMPLETE
**Commit:** `941dfe6` — `feat: 引擎 HTTP 客户端与 MiniMax 转写引擎`
**Date:** 2026-10-21

## Deliverables

- `backend/pom.xml` — added `org.wiremock:wiremock-standalone:3.13.1` (test scope; no fallback needed)
- `backend/src/main/java/com/waveminer/waveminer/engine/MultipartBuilder.java` — per brief, verbatim
- `backend/src/main/java/com/waveminer/waveminer/engine/EngineHttpClient.java` — per brief, verbatim (`EngineHttpResponse` record, 429/5xx retry up to `engineRetryAttempts` total attempts, backoff `backoffBaseMillis * attempt`, package-level `backoffBaseMillis = 1000`)
- `backend/src/main/java/com/waveminer/waveminer/engine/MinimaxAsrEngine.java` — per brief, verbatim (multipart `model`/`response_format=verbose_json`/`timestamp_level=sentence` + `file` appended last; `language` header omitted for auto; `POST {base}/v1/speech_to_text`; verbose_json parse with sec→ms ×1000 round and `speakerOf` digit extraction)
- `backend/src/main/java/com/waveminer/waveminer/config/EngineConfig.java` — `@Bean MinimaxAsrEngine(EngineHttpClient, EngineKeyProvider, AppProperties)`
- `backend/src/test/java/com/waveminer/waveminer/engine/MinimaxAsrEngineTest.java` — 6 tests, WireMock-based plain JUnit

## TDD Process

1. Added WireMock dep → wrote test first → ran `-Dtest=MinimaxAsrEngineTest` → **RED** (compile errors: classes don't exist) ✓
2. Implemented MultipartBuilder + EngineHttpClient + MinimaxAsrEngine + EngineConfig → ran targeted test → 5/6 pass, 1 failure (see deviation) → fixed test stubbing → **GREEN** ✓
3. Full suite `./mvnw -q test` → exit 0, **114 tests, 0 failures, 0 errors, 0 skipped** ✓
4. Committed exactly per required message ✓

## Deviations from Brief

1. **`rateLimitRetriesThenSucceeds` stubbing** — the brief's version registered two identical matchers (`POST /v1/speech_to_text`), one returning 429 and one 200. Under WireMock 3.13.1, with equal priority the **last-registered** matching stub wins immediately, so the first request got 200 directly (observed: 1 serve event, transcribe succeeded, `getAllServeEvents()` size 1 ≠ 2). The brief's stubbing as written cannot produce the 429→200 sequence regardless of engine retry logic.
   **Adaptation:** wrapped the two stubs in a WireMock scenario (`STARTED` → 429 + `willSetStateTo("ok")`, then `"ok"` → 200). Semantics unchanged: attempt 1 gets 429, attempt 2 gets 200, exactly 2 serve events — verifying the engine retry on 429 as intended. Also fixed the brief-implied `WireMock.STARTED` reference to `com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED` (constant lives in `Scenario`, not the client class).
   Engine implementation code was **not** changed — retry semantics are exactly per brief.
2. No other deviations. Implementation files are verbatim from the brief.

## Verification

- `./mvnw -q test -Dtest=MinimaxAsrEngineTest` → 6/6 pass
- `./mvnw -q test` → BUILD SUCCESS (exit 0), 114 tests across 19 suites, all green (includes new `MinimaxAsrEngineTest`)
- No Lombok used; all error messages in Chinese including required "原始响应已存档到 engine_raw/" in non-200 path

## Concerns

- None blocking. Note: engine re-sends the same `HttpRequest` instance across retries (per brief) — safe with `BodyPublishers.ofByteArray`.
