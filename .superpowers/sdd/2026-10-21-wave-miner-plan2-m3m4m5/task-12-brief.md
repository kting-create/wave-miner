### Task 12: 配置文档化（README/application.yml 注释）+ 打包冒烟

**Files:**
- Modify: `README.md`（转写章节）

**Interfaces:**
- Consumes: 全部后端配置键（Task 5 yml 已加）

- [ ] **Step 1: README 增加"转写"章节**

在 README 适当位置追加：

```markdown
## 转写（M3+M4）

任务创建时勾选"转写"，选择引擎与语言，任务完成后详情页提供时间轴对照与 `transcript.json/.srt/.txt` 三种产物下载。

### 引擎与 API Key

| 引擎 | 引擎 ID | 限制 | Key 配置 |
|---|---|---|---|
| MiniMax 语音识别 | `minimax` | ≤500 秒/50MB（超长自动静音切分） | 设置页 `minimax.apiKey` 或环境变量 `WAVEMINER_MINIMAX_API_KEY` |
| 阿里云百炼录音文件转写 | `aliyun-filetrans` | ≤12 小时/2GB | 设置页 `aliyun.apiKey` 或环境变量 `WAVEMINER_ALIYUN_API_KEY`（兼容 `DASHSCOPE_API_KEY`） |

- MiniMax baseUrl 默认中国站 `https://api.minimax.cn`，可用 `APP_MINIMAX_BASE_URL` 覆盖（国际站 `https://api.minimax.io`）。
- 阿里云 baseUrl 默认 `https://dashscope.aliyuncs.com/api/v1`，可用 `APP_ALIYUN_BASE_URL` 指向业务空间专属域名；默认模型 `qwen-audio-3.0-asr-flash-filetrans`（`APP_ALIYUN_MODEL` 可换 `paraformer-v2` 等）。
- 本地文件转写（阿里云）走 DashScope 上传凭证 + `oss://` 临时 URL，凭证限流 100 QPS。

### 相关配置（application.yml / 环境变量）

| 配置 | 默认 | 说明 |
|---|---|---|
| `app.engine-http-timeout-seconds` | 600 | 引擎单次 HTTP 请求超时 |
| `app.engine-retry-attempts` | 3 | 429/5xx/网络错误重试次数 |
| `app.transcribe-poll-interval-seconds` | 5 | 阿里云任务轮询间隔 |
| `app.transcribe-timeout-seconds` | 7200 | 转写任务整体超时 |
| `app.silence-threshold-db` / `app.silence-min-duration-sec` | -35 / 0.6 | 静音切分阈值 |
| `app.merge-gap-ms` | 800 | 跨分片段落合并间隙 |

### 进度推送

任务详情页使用 SSE（`GET /api/tasks/{id}/events`），断开自动回退轮询。访问令牌启用时 EventSource 走 `?token=`。
```

- [ ] **Step 2: 打包冒烟**

```bash
cd frontend && npm run build && cd ../backend && ./mvnw -q package -DskipTests
java -jar target/wave-miner-0.0.1-SNAPSHOT.jar &
sleep 8
curl -sf http://127.0.0.1:8080/api/engines | head -c 400
curl -sf http://127.0.0.1:8080/api/settings | head -c 200
kill %1
```

Expected: `/api/engines` 返回两个引擎 JSON（`configured` 视 key 而定）；`/api/settings` 返回两个设置项。

- [ ] **Step 3: Commit**

```bash
git add README.md && git commit -m "docs: 转写引擎配置与使用说明"
```

---

