# wave-miner 会话交接（计划 2 执行至 T13 中段，T9-T12 完成；T13 真机验收 8/11 项已过）

> 用途：计划 2 在"T13 真机验收中段"按需求方指令暂停（阿里云链路已真机打通，剩 MiniMax 相关项与收尾）。
> 最后更新：2026-10-21 晚。本文档 + 计划 2 文档 + `.superpowers/sdd/2026-10-21-wave-miner-plan2-m3m4m5/`（ledger 与任务报告，随仓库归档）即可无损续作。

## 0. 工作约定（需求方明确的规则，必须遵守）

1. **以官方文档为准**：API 设计/参数先查官方文档再实现；真机遇到契约偏差以**实际响应存档**（engine_raw）+ 官方文档双重确认。
2. **细节不用反复确认**：自行裁定并记录 Ruling；只有不可逆/有副作用/每条路都是猜才停下来问。
3. **稳定、能用的质量标准**：个人项目，优先稳定运行；错误信息一律中文。
4. **跨平台**：新代码/测试双平台可用（Windows 验证/Linux 部署）；测试禁止依赖平台专有行为；外部进程走 `ProcessRunner`。
5. JDK 21 + 虚拟线程；不使用 Lombok。
6. **执行方式**：superpowers:subagent-driven-development 逐任务执行（TDD + 独立审查 + conventional commit）。

## 1. 当前进度

**T1-T12 全部完成并审查通过；T13 进行到一半（8/11 项真机验收通过，6 个验收缺陷已修复）。全量测试 127 个全部通过**（`cd backend && ./mvnw -q test`）；前端 vitest 11 例 + build 全绿。

| 任务 | 内容 | 提交 |
|---|---|---|
| T1-T8 | （见 git 历史，计划 2 前半段全部完成） | 4a65762..6558d67 |
| 基线修复 | Windows 测试跨平台（Spring 测试类换 DriverManagerDataSource 释放 SQLite 句柄；FfmpegServiceTest 路径断言平台中立） | `56ac533` |
| T9 | SSE 进度接口（TaskSseRegistry 15s 心跳/终态 complete + GET /api/tasks/{id}/events） | `9319ec6` |
| T10 | 前端一：types/client/timeline 工具（vitest 先行）/NewTaskView 转写卡 + artifactUrl 按 `/` 分段编码（fix） | `bbbb6cc` + `813d6cb` |
| T11 | 前端二：时间轴对照联动 + SSE 订阅（onerror 回退轮询）+ engine_raw 查看器 + 重试后重订阅（fix） | `8135679` + `2e55b18` |
| T12 | README 转写章节 + 打包冒烟（/api/engines、/api/settings） | `85fef0b` |
| T13（部分） | 真机验收 + 6 个缺陷修复（见 §3）+ 设置页补齐 | `6b380dd` `ddbf9a9` `fd2a8cf` `6a67848` `2d1234e` `c242ab5` |

## 2. T13 真机验收状态（11 项清单）

**已过（9 项）**：1 设置脱敏 ✅；2 engines 列表 ✅；3 短音频转写 ✅（阿里云真转写，transcript.json/srt/txt 产物正确）；4 说话人分离 ✅（diarize=true 全链路，segments.speaker 落库；纯"中英混合"素材未单独构造，language=auto 正常）；6 阿里云引擎 ✅（ASCII 与中文文件名都 COMPLETED）；8 未配 key 报错 ✅（中文，TRANSCRIBE FAILED）；9 SSE 实时 ✅（connected/progress/stage/终态 complete + 心跳全捕获）；10 令牌 ✅（无 token 401 / header / ?token=）；11 错误 key engine_raw 存档+下载 ✅。

**待做（2 项 + 收尾）**：
- **项 3 MiniMax 部分 + 项 7 超长切分**：需 MiniMax 中国站 key（需求方暂未提供，已暂停）。素材已备好：`C:\Users\Public\long-audio.mp3`（616 秒，由用户 56s mp3 循环拼接，>500s 触发 minimax 500s 限额切分）。
- **项 5 直链 mp4 全流程**：未跑。yt-dlp 2026.08.19 已装于 `C:\tools\ytdlp\yt-dlp.exe`（服务器启动参数需带 `--app.ytdlp-path`）。
- 收尾：全量测试 + 最终全分支审查（superpowers requesting-code-review）+ 更新本文档为完成态 + 删除 SDD 工作区。

## 3. T13 验收发现并已修复的缺陷（各已提交，均 TDD）

1. `6b380dd` 缺少访问令牌 → 500（拦截器 given=null NPE）→ 401。
2. `ddbf9a9` 产物下载多段路径 404：`{name:.+}` 在 Spring Boot 4 PathPatternParser 不跨 `/` → 改 `{*name}` 捕获（engine_raw/ 下载实锤）。
3. `fd2a8cf` 计划缺口：验收要求设置页但无任何任务建设置页 UI → 补 SettingsView.vue（/settings 路由 + 菜单项）。
4. `6a67848` 阿里云上传凭证解析 `output.data` → 实际信封是**顶层 `data`**（真机原始响应存档实锤；getPolicy 与转写任务接口信封不同）。
5. `2d1234e`→`c242ab5`（两步演进）提交转写 `REQUEST_INVALID_FILE_URL_VALUE`：**file_urls 必须是 `oss://<objectKey>`（无 bucket 前缀）且提交请求必须带 `X-DashScope-OssResourceResolve: enable` 头**——计划 T8 漏了这个头（中间的 bucket 前缀方案已被撤销）。
6. 环境裁定：原 ffmpeg（第三方 nightly，任何输出文件 Permission denied）→ winget 安装 Gyan ffmpeg 9.0.2，服务器参数显式指定 `--app.ffmpeg-path/--app.ffprobe-path`。

## 4. 验收环境（本机现状）

- 服务器：`java -jar backend/target/wave-miner-0.0.1-SNAPSHOT.jar --app.data-dir=D:/code/wave-miner/data --app.access-token=wave2026 --app.ffmpeg-path=<gyan>\ffmpeg.exe --app.ffprobe-path=<gyan>\ffprobe.exe --app.ytdlp-path=C:\tools\ytdlp\yt-dlp.exe`，监听 8080，**仍在运行**（本次暂停未停服）。
- **API key 只存 `data/wave-miner.db`（SQLite）；`data/` 在 .gitignore，git 追踪 0 个文件，密钥不会入库**（已验证）。阿里云 key 已由需求方填入；MiniMax key 未填。
- Windows 注意：shell 工具会杀子进程树，服务器用 Start-Process 启动，用 `Get-NetTCPConnection -LocalPort 8080` 定位真实监听 pid 再管理；PowerShell 5.1 无 curl 别名冲突用 curl.exe；PS 变量名大小写不敏感（循环里 $T/$t 会互踩）。
- 工具链：Gyan ffmpeg/ffprobe 9.0.2、yt-dlp 2026.08.19、JDK 21.0.9。

## 5. 续作指引（零上下文）

1. 读本文档 + `docs/superpowers/plans/2026-10-21-wave-miner-plan2-m3m4m5.md`（Task 13 原文）+ `.superpowers/sdd/2026-10-21-wave-miner-plan2-m3m4m5/progress.md`（ledger，全部 Ruling/deferred minor 在内）+ task-13-report.md（6 个修复的完整记录）。
2. 验证基线：`cd backend && ./mvnw -q test`（127 全绿）；`cd ../frontend && npm run test && npm run build`（全绿）。
3. 向需求方索取 MiniMax key（设置页填入或 `WAVEMINER_MINIMAX_API_KEY`），完成项 3-MiniMax/项 5/项 7：
   - 项 3：小音频（`C:\Users\Public\test-clip.mp4` 10s 可复用）走 minimax 引擎全流程。
   - 项 7：上传 `C:\Users\Public\long-audio.mp3`（616s）走 minimax，观察静音切分（日志/进度"分段转写"）与合并后产物。
   - 项 5：创建 URL 任务跑直链媒体（需含人声的直链；本机无 yt-dlp 时服务器参数必须带 --app.ytdlp-path）。
4. 验收全过后：最终全分支 review（merge-base 4a65762..HEAD，最 capable 模型）→ 清 deferred minor → 更新本文档为完成态 → 删除 `.superpowers/sdd/2026-10-21-wave-miner-plan2-m3m4m5/` 前先归档提交（项目惯例）。

## 6. 实战注意事项（新增，接 HANDOFF 旧 §7）

1. DashScope 上传凭证接口（GET /uploads?action=getPolicy）信封是**顶层 `data`**；转写任务接口（/services/audio/asr/transcription、/tasks/{id}）信封是 `output.*`——两套不要混。
2. filetrans 提交：`oss://<objectKey>` 无 bucket 前缀 + 请求头 `X-DashScope-OssResourceResolve: enable`，缺头必报 REQUEST_INVALID_FILE_URL_VALUE。
3. Spring Boot 4 PathPatternParser：`{name:.+}` 不跨段，跨段路径用 `{*name}`（捕获值带前导 `/`，需剥离）。
4. Spring 测试 + SQLite + @TempDir 在 Windows 必挂：测试类加 `spring.datasource.type=DriverManagerDataSource`（T1-T8 各 API 测试类已如此）。
5. SSE MockMvc 测试：Spring 7 无 `isAsyncComplete()`，用 dispatch latch（getAsyncResult + asyncDispatch），见 TaskSseApiTest。
6. WireMock 3.x 同 URL 多响应必须用 scenario（last-stub-wins）。
