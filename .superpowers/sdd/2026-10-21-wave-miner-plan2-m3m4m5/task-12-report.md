# Task 12 报告：配置文档化 + 打包冒烟

## 状态
DONE

## 完成内容

### Step 1: README 增加"转写"章节
- 在 `README.md` 的「配置」章节之后、「测试」章节之前插入 brief 中的「## 转写（M3+M4）」章节全文（逐字照抄，含引擎与 API Key 表、相关配置表、进度推送说明），共新增 30 行。
- 选位理由：该章节主体是引擎 Key 与 `app.*` 配置项，紧接现有「配置」章节最自然；「测试」及之后的 Windows/Linux 部署章节保持原序不动。

### Step 2: 打包冒烟（PowerShell 等价流程）
1. **前端构建**：`cd frontend; npm run build` → 通过（vue-tsc + vite，1603 modules，368ms；仅既有的 chunk >500kB 警告，非错误）。
2. **后端打包**：`cd backend; .\mvnw.cmd -q package -DskipTests` → 通过（Maven frontend 插件顺带重建前端，产物 `backend/target/wave-miner-0.0.1-SNAPSHOT.jar`，约 37.8MB）。
3. **端口检查**：`Get-NetTCPConnection -LocalPort 8080` → 启动前 8080 空闲。
4. **后台启动**：`Start-Process java -jar <jar> -PassThru`（stdout/stderr 重定向到 `%TEMP%\opencode\wm-smoke-*.log`），日志确认 `Started WaveMinerApplication in 2.609 seconds`（PID 25708）。
5. **端点验证**（`Invoke-RestMethod`）：
   - `GET /api/engines` → 返回两个引擎 JSON：
     - `minimax`：maxDurationSec=500、maxSizeBytes=52428800（50MB），带 `configured` 字段（=false，未配 key）；
     - `aliyun-filetrans`：maxDurationSec=43200（12h）、maxSizeBytes=2147483648（2GB），带 `configured` 字段（=false）。
     - 判定符合预期：两引擎 + `configured` 字段齐全，值随 key 而定（本机未配 key 故为 false）。
   - `GET /api/settings` → 返回两个设置键：`aliyun.apiKey`、`minimax.apiKey`（value 空、configured=false，符合未配 key 的预期）。
6. **清理**：`Stop-Process -Id 25708`，端口 8080 释放确认。

### Step 3: Commit
- `85fef0b docs: 转写功能与配置使用说明`（仅 README.md，+30 行；`.superpowers/` 下的过程元数据未纳入提交）。

## 备注
- 控制台输出中引擎 displayName 显示为乱码，是 PowerShell GBK 控制台渲染 UTF-8 JSON 的显示层问题，接口数据本身为正确 UTF-8 中文，非后端缺陷。
- 启动日志另见 `StartupRecovery 启动恢复检查完成`，无报错。
