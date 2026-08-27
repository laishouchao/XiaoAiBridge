# XiaoAiBridge

把小米超级小爱 (`com.miui.voiceassist`) 的 AI 能力暴露为 **OpenAI 兼容 HTTP API** 的 Xposed 模块（LibXposed API 102）。

## 原理

通过 Hook 超级小爱内部的 `cr0.g`（会话管理器），使用 `Nlp.RequestLargeLanguageModelContent` 将文本直接注入 NLP 管线，绕过语音识别（ASR），响应通过 `Template.ToastStream` 流式提取。

```
用户请求 → HTTP Server (0.0.0.0:8787)
         → cr0.g.getInstance().sendEvent(RequestLargeLanguageModelContent)
         → 超级小爱 NLP 引擎 (唯一, 不随 model 参数切换)
         → Template.ToastStream 流式响应
         → OpenAI 兼容 JSON / SSE 返回
```

## 功能

- **OpenAI 兼容**：`POST /v1/chat/completions`（支持流式 SSE + 非流式）
- **引用文献（annotations）**：回答来自联网检索时，引用来源以 OpenAI 标准 `message.annotations[].url_citation` 返回（含 `title`/`url`），正文不再混入引用 ID 噪声
- **超长输入自动分块**：单次请求超过 ~20KB 时，模块自动按块滚动摘要、携带状态逐块注入，客户端无感地突破单次 ~24KB 上限（可在设置中关闭）
- **单一模型**：`model` 字段只有一个值 `XiaoAi`（底层为超级小爱 NLP 引擎，见下方"模型"章节）
- **多轮对话**：模块无状态，由客户端携带完整 messages 历史实现
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
  -d '{"model":"XiaoAi","messages":[{"role":"user","content":"你好"}]}'

# 模型列表
curl http://127.0.0.1:8787/v1/models
```

## API 端点

| 端点 | 方法 | 说明 |
|------|------|------|
| `/v1/chat/completions` | POST | OpenAI 对话（流式/非流式） |
| `/v1/chat` | POST | 简化对话（text → reply；`chatId`/`agentId` 参数未透传，仅为占位） |
| `/v1/chat/reset` | POST | 空操作（会话由超级小爱管理，无本地会话可重置） |
| `/v1/models` | GET | 模型列表（仅一个 `XiaoAi`） |
| `/v1/tools` | GET | 工具信息 |
| `/health` | GET | 健康检查 |
| `/v1/admin/status` | GET | 服务状态 |
| `/v1/admin/logs` | GET | 请求日志（最近 100 条） |
| `/v1/admin/reload` | GET | 配置热重载 |
| `/openapi.json` | GET | OpenAPI 文档 |

## 模型

> **只有一个模型：`XiaoAi`。**
>
> `model` 参数不会透传给超级小爱（Hook 层的 `agentId` 变量未被使用），它仅作为 OpenAI 协议中的名称占位，方便客户端配置。传入任何其他字符串（如 `gpt-4o`）也不会报错，行为与 `XiaoAi` 完全相同，响应中原样回显客户端传入的名称。

| 项目 | 说明 |
|------|------|
| 唯一模型名 | `XiaoAi` |
| 底层引擎 | 超级小爱 NLP 管线（由小爱 App 自身决定，模块不可选） |
| `model` 参数作用 | 仅占位回显，不影响任何行为 |
| 请求拼接方式 | system 消息以 `系统设定：` 前缀注入，user/assistant 消息按原文裸拼接直发 |

超级小爱底层使用小米自研大模型。早期版本搭载 **MiLM-6B**（64 亿参数预训练语言模型），当前超级小爱已升级至 **MiMo 系列**大模型。模块无法选择后端模型——实际使用哪个模型由超级小爱 App 自身决定。

### 引擎参数（实测）

| 指标 | 数值 | 说明 |
|------|------|------|
| 参数量 | ~6.4B | 小米 MiLM-6B 初始模型（后端，模块不可选） |
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
| `model` | ⚠️ 仅占位 | String | 不透传给小爱，任何值均同 `XiaoAi`，响应原样回显 |
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

### 引用文献（annotations）

小爱回答若来自联网检索，会先下发 `Template.LLMReferenceInfo` 事件（引用来源列表）。模块将其转换为 OpenAI 标准的 `annotations` 字段，正文不再混入引用 ID 噪声：

```json
{
  "message": {
    "role": "assistant",
    "content": "现在是北京时间17时08分。",
    "annotations": [
      {
        "type": "url_citation",
        "url_citation": {
          "start_index": 0,
          "end_index": 0,
          "title": "北京时间在线校准",
          "url": "https://time.tianqi.com/"
        }
      }
    ]
  }
}
```

- `title` ← 引用条目标题；`url` ← 引用页面真实链接
- `start_index`/`end_index` 恒为 0——小爱不提供引用在正文中的位置区间，客户端按整体来源展示即可
- 流式模式下，annotations 作为独立 chunk 在正文之后、`finish_reason` 之前发送
- 纯闲聊类回答（未触发联网检索）无 `annotations` 字段

### 多轮对话与 System Prompt

- **多轮对话**: 模块无状态，客户端每次请求需携带完整 messages 历史（超长时自动分块，见下节）
- **System Prompt**: 以 `"系统设定："` 前缀注入，随拼接文本一起发送
- **user/assistant 消息**: 按原文裸拼接直发，不添加 `用户:`/`助手:` 角色前缀
- **JSON 模式**: `response_format: {"type": "json_object"}` 时注入指令要求 AI 只输出 JSON

### 超长输入自动分块（v5.2.0）

超级小爱单次注入上限实测 ~24.7KB，且每次请求为独立会话（服务端不记忆上文）。超过 ~20KB 的请求由模块自动处理，客户端无需任何改动：

```
长输入 (如 57KB)
  ├─ 按换行边界切成多个 ≤18KB 块
  ├─ 块1 → "详细摘要(≤600字, 保留关键事实)"        → 摘要1
  ├─ 摘要1 + 块2 → "融合更新摘要"                   → 摘要2
  ├─ 摘要N-1 + 块N → "融合更新摘要"                 → 摘要N
  └─ 摘要N + 用户最新提问 → 最终回答
```

- **末条 user 消息 ≤ 4KB 时原文保留**，只有之前的 system/历史消息进入摘要链；末条消息本身超长时整体并入摘要
- 真机实测：57KB 文档（关键事实分散 3 区块）→ 3 块摘要链 → 最终回答保留全部事实，总耗时 ~14s
- **代价**：每块一次小爱调用（2-4s/块），流式模式下首字延迟随块数线性增加；摘要有信息损耗（关键事实保留良好，措辞细节不保）
- 任一块失败立即返回 `500`（`code: chunk_failed`），不会静默丢内容
- 可在模块设置中关闭（关闭后行为同旧版：超限请求等待 ~25s 后报错）

## 已知限制

1. **输入字节上限 ~24.7KB** — 超过此限制超级小爱返回错误；默认开启的自动分块会在 ~20KB 时接管（关闭后需客户端自行截断）
2. **并发上限 ~2** — 超过后请求超时，建议客户端串行或限流
3. **输出无硬性截断** — `max_tokens` 仅作为提示，实际输出由 AI 自行决定
4. **token 计数始终为 0** — `usage` 字段不反映真实 token 数
5. **引用位置缺失** — `annotations` 的 `start_index`/`end_index` 恒为 0（小爱不提供引用在正文中的位置）
6. **超限请求耗时 ~25s** — 关闭自动分块时的超时等待行为
7. **模型不可选** — 仅一个 `XiaoAi` 模型，`model` 参数为占位，无法切换后端引擎

## 配置

模块设置界面支持：
- HTTP 端口（默认 8787）
- API Token 鉴权（留空 = 不鉴权）
- 限流（次/分钟，0 = 关闭）
- 请求日志（最近 100 条）
- AI 调用失败自动重试
- 超长输入自动分块+滚动摘要
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
git tag v5.2.0 && git push origin v5.2.0
```

## 版本历史

- **v5.2.0** (2026-08-27): 新增超长输入自动分块——超过 ~20KB 自动按块滚动摘要、末条提问原文保留，客户端无感突破单次 ~24KB 上限（真机实测 57KB→3 块→全事实保留，~14s）；设置页新增开关
- **v5.1.2** (2026-08-27): 新增引用文献支持——`Template.LLMReferenceInfo` 转换为 OpenAI 标准 `annotations[].url_citation`（流式+非流式），修复回复正文混入引用 ID 噪声的问题
- **v5.1.1** (2026-08-27): 模型列表精简为单一 `XiaoAi`，移除全部别名与前缀分支；文档修正至真实情况——`model`/`chatId` 参数不透传、`/v1/chat/reset` 为空操作
- **v5.1.0** (2026-08-18): 精简重构，移除 LLM 代理/exec/root 管理，仅保留核心 AI→API 功能
- **v5.0.1** (2026-08-18): 修复 root 状态检测，UI 文案统一为"超级小爱"
- **v5.0.0** (2026-08-18): Material Design 卡片式 UI 重构，GitHub Actions CI/CD
- **v4.x**: 探索 Channel/ChannelListener 通信路径
- **v3.x**: 响应解析修复（ToastStream markdown_text 提取）
- **v2.0**: HTTP Server + OpenAI 兼容 API
- **v1.0**: 初始版本

## 许可

MIT License
