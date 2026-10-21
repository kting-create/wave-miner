# Task 2 Report: 转写引擎 SPI 类型 + EngineRegistry + GET /api/engines

**Status:** COMPLETE
**Commit:** `306cacf` — `feat: 转写引擎 SPI 与引擎注册表、GET /api/engines`（13 files, +203 lines）

## What was done

TDD 流程（先测后码）：

1. **Red:** 先创建 `EngineRegistryTest`（3 个用例），运行确认编译失败（`EngineRegistry`/`TranscriptionEngine`/`EngineException` 不存在）。
2. **Green:** 按 brief 逐字实现全部 11 个生产文件：
   - `engine/Segment.java`、`EngineInfo.java`、`TranscribeOptions.java`（含 `of(null/blank → "auto")`）、`TranscribeResult.java`
   - `engine/ProgressCallback.java`（`cancelled()` default false、`noop()` 静态工厂）
   - `engine/TranscriptionEngine.java`（`id()` / `info()` / `transcribe(Path, TranscribeOptions, ProgressCallback)`）
   - `engine/EngineException.java`（3 个构造器 + `getRawResponse()` 可 null）、`EngineCancelledException.java`
   - `engine/EngineRegistry.java`（@Component，`ObjectProvider` 注入，`all()` = `orderedStream().toList()`，未知 id → `EngineException("未知转写引擎: " + id)`）
   - `engine/EngineKeyProvider.java` + `SettingsEngineKeyProvider.java`（minimax→`minimax.apiKey`/`WAVEMINER_MINIMAX_API_KEY`；aliyun-filetrans→`aliyun.apiKey`/`WAVEMINER_ALIYUN_API_KEY`/`DASHSCOPE_API_KEY`；消费 Task 1 `SettingService.get`）
   - `api/EngineController.java`（GET /api/engines → `EngineView` record，`configured` = key 非空；构造参数为 `ObjectProvider<TranscriptionEngine>`，空引擎列表可注入）
3. **全量测试 + 提交。**

## Test evidence

- 单测（先失败）：`./mvnw -q test -Dtest=EngineRegistryTest` → 编译错误（红）。
- 实现后单测：`EngineRegistryTest` **Tests run: 3, Failures: 0, Errors: 0**。
- 全量：`cd backend && ./mvnw test` → **Tests run: 76, Failures: 0, Errors: 0, Skipped: 0，BUILD SUCCESS**（含 `WaveMinerApplicationTests` 上下文加载，验证空引擎 Bean 下 `EngineRegistry`/`EngineController` 均可装配）。

## Deviations

1. **测试 helper 一处微调**（brief 原文缺陷）：brief 的 `registry()` 用 `Mockito.when(provider.orderedStream()).thenReturn(List.of(engines).stream())`，同一 stream 实例被第二次 `all()` 消费时报 `IllegalState: stream has already been operated upon or closed`（`getReturnsEngineById` 先 `get` 再 `all` 正好触发）。改为 `thenAnswer(inv -> List.of(engines).stream())` 每次返回新 stream——与真实 `ObjectProvider` 语义一致。**生产代码零改动**，3 个用例意图（按 id 查找 / 中文报错 / 空注册表）不变。
2. 无其他偏离；所有生产类型与 brief 逐字一致。

## Concerns

- `SettingsEngineKeyProvider` 依赖进程环境变量兜底，测试未覆盖 env 路径（brief 未要求；M3 引擎实现任务会间接覆盖）。
- `EngineController` 无集成测试（brief 仅要求 `EngineRegistryTest`）；上下文加载测试已证明装配正确，后续任务会有引擎 Bean 落地后可补。

## Self-review

- [x] 所有签名与 brief Interfaces 块精确一致（后续任务依赖面）。
- [x] 无 Lombok；异常/报错消息为中文。
- [x] `ObjectProvider` 注入保留（上下文空引擎 Bean 可启动）。
- [x] 全量 76 测试通过；commit message 与要求逐字一致。
