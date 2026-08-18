# XiaoAiBridge

把小米超级小爱 (`com.miui.voiceassist`) 的 AI 能力暴露为 **OpenAI 兼容 HTTP API** 的 Xposed 模块（LibXposed API 102）。

## 原理

通过 Hook 超级小爱内部的 `cr0.g`（会话管理器），使用 `Nlp.RequestLargeLanguageModelContent` 将文本直接注入 NLP 管线，绕过语音识别（ASR），响应通过 `Template.ToastStream` 流式提取。

```
用户请求 → HTTP Server (127.0.0.1:8787)
         → cr0.g.getInstance().sendEvent(RequestLargeLanguageModelContent)
         → 小爱 NLP 引擎
         → Template.ToastStream 流式响应
         → OpenAI 兼容 JSON / SSE 返回
```

## 功能

- **OpenAI 兼容**：`POST /v1/chat/completions`（支持流式 SSE + 非流式）
- **多模型**：`voiceassist.main` / `voiceassist.chat` / `voiceassist.nlp` / `voiceassist.skill` / `xiaomi.ai`
- **多轮会话**：`user` 字段控制会话上下文
- **管理**：`GET /v1/models` / `GET /health` / 配置热重载
- **零外部依赖**：全部走本地超级小爱引擎，无需 API Key

## 安装

1. 下载 APK 安装
2. LSPosed（1.9.2+）启用模块，作用域勾选 `com.miui.voiceassist`
3. 重启超级小爱
4. 确认 HTTP 服务已启动（`adb logcat -s XiaoAiBridge`）

## 使用

```bash
# Base URL
http://127.0.0.1:8787/v1

# 对话
curl -X POST http://127.0.0.1:8787/v1/chat/completions \
  -H "Content-Type: application/json" \
  -d '{"model":"voiceassist.main","messages":[{"role":"user","content":"你好"}]}'

# 模型列表
curl http://127.0.0.1:8787/v1/models
```

## API 端点

| 端点 | 方法 | 说明 |
|------|------|------|
| `/v1/chat/completions` | POST | OpenAI 对话（流式/非流式） |
| `/v1/chat` | POST | 简化对话（text → reply） |
| `/v1/chat/reset` | POST | 重置会话 |
| `/v1/models` | GET | 可用模型列表 |
| `/v1/tools` | GET | 工具信息 |
| `/health` | GET | 健康检查 |
| `/v1/admin/status` | GET | 服务状态 |
| `/v1/admin/logs` | GET | 请求日志（最近 100 条） |
| `/v1/admin/reload` | GET | 配置热重载 |
| `/openapi.json` | GET | OpenAPI 文档 |

## 模型

超级小爱底层使用小米自研大模型。早期版本搭载 **MiLM-6B**（64 亿参数预训练语言模型），当前超级小爱已升级至 **MiMo 系列**大模型。

| 模型名 | 说明 |
|--------|------|
| `voiceassist.main` | 默认超级小爱（主对话引擎） |
| `voiceassist.chat` | 聊天模式 |
| `voiceassist.nlp` | NLP 语义理解 |
| `voiceassist.skill` | 技能服务 |
| `xiaomi.ai` | 小米 AI 通用引擎 |

### 模型参数

| 指标 | 数值 | 说明 |
|------|------|------|
| 参数量 | ~6.4B | 小米 MiLM-6B 初始模型 |
| 架构 | GPT-like | 预训练语言模型 |
| 上下文窗口 (API 层) | ~24KB | 本模块 HTTP API 实测字节上限 |
| 上下文窗口 (中文) | ~8,200 字符 | UTF-8 编码下 3 字节/字符 |
| 上下文窗口 (ASCII) | ~24,600 字符 | 纯英文场景 |
| 最大输出 | ~6,254 字符 | 实测最大输出（非硬性截断） |
| 并发上限 | ~2 | 受超级小爱后端限制 |
| 平均延迟 | 2-3 秒 | 简单问答 |
| 超时阈值 | 60 秒 | Socket SO_TIMEOUT |

> **注意**: 超级小爱服务端可能已升级到更新的 MiMo 模型，但 API 层的上下文限制由本模块的桥接机制决定，与服务端模型能力不同步。

## 参数支持

### OpenAI 兼容参数

| 参数 | 支持度 | 类型 | 生效方式 |
|------|--------|------|----------|
| `model` | ✅ 完全支持 | String | 映射为 agentId，未知模型回退默认 |
| `messages` | ✅ 完全支持 | Array | 拼接为文本发送给超级小爱 |
| `stream` | ✅ 完全支持 | Boolean | `true` 时返回 SSE 流式输出 |
| `response_format` | ✅ 支持 | Object | `{"type":"json_object"}` 时注入 JSON 指令 |
| `max_tokens` | ⚠️ 提示模式 | Integer | 非硬性截断，作为提示语注入 |
| `temperature` | ⚠️ 提示模式 | Float | 非原生采样，作为提示语注入 |
| `tools` | ❌ 不支持 | Array | v5.1.0 已移除 LLM 代理 |
| `top_p` | ❌ 不支持 | Float | — |
| `n` | ❌ 不支持 | Integer | — |
| `stop` | ❌ 不支持 | String/Array | — |
| `presence_penalty` | ❌ 不支持 | Float | — |
| `frequency_penalty` | ❌ 不支持 | Float | — |
| `logprobs` | ❌ 不支持 | Boolean | — |
| `seed` | ❌ 不支持 | Integer | — |
| `user` | ❌ 忽略 | String | — |

### max_tokens 映射规则

`max_tokens` 并非硬性 token 限制，而是根据数值大小注入对应的提示语：

| max_tokens 值 | 注入的提示语 |
|---------------|-------------|
| < 100 | "回答要求：非常简短，一句话以内。" |
| < 300 | "回答要求：简洁，控制在几行内。" |
| > 2000 | "回答要求：详细完整，尽量展开论述，条理清晰。" |
| 其他 / 不传 | 无额外提示 |

### temperature 映射规则

| temperature 值 | 注入的提示语 |
|---------------|-------------|
| < 0.5 | "回答要求：严谨、准确、简洁、事实导向，避免发散。" |
| > 1.2 | "回答要求：有创意、发散、生动，可以适当发挥。" |
| 其他 / 不传 | 无额外提示 |

### 多轮对话与 System Prompt

- **多轮对话**: 将多条 messages 按顺序传入，模块自动拼接为上下文文本
- **System Prompt**: 以 `"系统设定："` 前缀注入，超级小爱会参考角色设定
- **voiceassist.main 特殊处理**: 该模型跳过 system 默认人设注入，直接发送用户消息
- **JSON 模式**: `response_format: {"type": "json_object"}` 时注入指令要求 AI 只输出 JSON

## 已知限制

1. **输入字节上限 ~24.7KB** — 超过此限制超级小爱返回错误，客户端需截断长文本
2. **并发上限 ~2** — 超过后请求超时，建议客户端串行或限流
3. **输出无硬性截断** — `max_tokens` 仅作为提示，实际输出由 AI 自行决定
4. **token 计数始终为 0** — `usage` 字段不反映真实 token 数
5. **响应内容噪声** — 回复开头可能包含随机字符串（桥接层噪声）
6. **超限请求耗时 ~25s** — 超时等待而非立即拒绝

## 配置

模块设置界面支持：
- HTTP 端口（默认 8787）
- API Token 鉴权（留空 = 不鉴权）
- 限流（次/分钟，0 = 关闭）
- 请求日志（最近 100 条）
- AI 调用失败自动重试
- Verbose 调试日志

## 安全

- 监听 `0.0.0.0`（局域网可访问），建议配置 API_TOKEN
- 可选 Token 鉴权（`Authorization: Bearer <token>` 或 `X-API-Key`）
- 默认不启用鉴权，生产环境务必设置 Token

## 开发

```bash
git clone https://github.com/laishouchao/XiaoAiBridge
# Android Studio 打开，编译即可
# 推送 tag 触发 GitHub Actions 自动构建 Release
git tag v5.1.0 && git push origin v5.1.0
```

## 版本历史

- **v5.1.0** (2026-08-18): 精简重构，移除 LLM 代理/exec/root 管理，仅保留核心 AI→API 功能
- **v5.0.1** (2026-08-18): 修复 root 状态检测，UI 文案统一为"超级小爱"
- **v5.0.0** (2026-08-18): Material Design 卡片式 UI 重构，GitHub Actions CI/CD
- **v4.x**: 探索 Channel/ChannelListener 通信路径
- **v3.x**: 响应解析修复（ToastStream markdown_text 提取）
- **v2.0**: HTTP Server + OpenAI 兼容 API
- **v1.0**: 初始版本

## 许可

MIT License
