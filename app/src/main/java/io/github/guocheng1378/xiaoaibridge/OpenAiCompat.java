package io.github.guocheng1378.xiaoaibridge;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * OpenAI Chat Completions 格式转换
 */
public class OpenAiCompat {

    /** 构建非流式 OpenAI 响应 (annotations: 引用文献, 可为 null) */
    public static JSONObject buildSyncResponse(String model, String content,
            java.util.List<JSONObject> annotations) {
        try {
            JSONObject resp = new JSONObject();
            resp.put("id", "chatcmpl-" + System.currentTimeMillis());
            resp.put("object", "chat.completion");
            resp.put("created", System.currentTimeMillis() / 1000);
            resp.put("model", model);

            JSONArray choices = new JSONArray();
            JSONObject choice = new JSONObject();
            choice.put("index", 0);

            JSONObject msg = new JSONObject();
            msg.put("role", "assistant");
            msg.put("content", content == null ? "" : content);
            if (annotations != null && !annotations.isEmpty()) {
                JSONArray anns = new JSONArray();
                for (JSONObject a : annotations) anns.put(a);
                msg.put("annotations", anns);
            }
            choice.put("message", msg);
            choice.put("finish_reason", "stop");
            choices.put(choice);
            resp.put("choices", choices);

            JSONObject usage = new JSONObject();
            usage.put("prompt_tokens", 0);
            usage.put("completion_tokens", 0);
            usage.put("total_tokens", 0);
            resp.put("usage", usage);
            return resp;
        } catch (Exception e) {
            return errorResponse("Failed to build response: " + e.getMessage());
        }
    }

    /** 构建流式 SSE 的 annotations chunk (引用文献, 在正文之后发送) */
    public static String buildStreamAnnotationsChunk(String model, java.util.List<JSONObject> annotations) {
        try {
            JSONObject chunk = new JSONObject();
            chunk.put("id", "chatcmpl-" + System.currentTimeMillis());
            chunk.put("object", "chat.completion.chunk");
            chunk.put("created", System.currentTimeMillis() / 1000);
            chunk.put("model", model);

            JSONArray choices = new JSONArray();
            JSONObject choice = new JSONObject();
            choice.put("index", 0);

            JSONObject delta = new JSONObject();
            JSONArray anns = new JSONArray();
            for (JSONObject a : annotations) anns.put(a);
            delta.put("annotations", anns);
            choice.put("delta", delta);
            choice.put("finish_reason", JSONObject.NULL);

            choices.put(choice);
            chunk.put("choices", choices);
            return "data: " + chunk.toString() + "\n\n";
        } catch (Exception e) {
            return "";
        }
    }

    /** 构建流式 SSE 的 OpenAI chunk */
    public static String buildStreamChunk(String model, String content, String finishReason) {
        try {
            JSONObject chunk = new JSONObject();
            chunk.put("id", "chatcmpl-" + System.currentTimeMillis());
            chunk.put("object", "chat.completion.chunk");
            chunk.put("created", System.currentTimeMillis() / 1000);
            chunk.put("model", model);

            JSONArray choices = new JSONArray();
            JSONObject choice = new JSONObject();
            choice.put("index", 0);

            JSONObject delta = new JSONObject();
            delta.put("role", "assistant");
            if (content != null) delta.put("content", content);
            choice.put("delta", delta);

            if (finishReason != null) {
                choice.put("finish_reason", finishReason);
            } else {
                choice.put("finish_reason", JSONObject.NULL);
            }

            choices.put(choice);
            chunk.put("choices", choices);
            return "data: " + chunk.toString() + "\n\n";
        } catch (Exception e) {
            return "";
        }
    }

    /** 构建模型列表 (仅一个模型 XiaoAi, 底层为超级小爱 NLP 引擎, model 参数不透传) */
    public static JSONObject buildModelList() {
        try {
            JSONObject resp = new JSONObject();
            resp.put("object", "list");
            JSONArray data = new JSONArray();

            JSONObject model = new JSONObject();
            model.put("id", Config.MODEL_NAME);
            model.put("object", "model");
            model.put("created", System.currentTimeMillis() / 1000);
            model.put("owned_by", "xiaoaibridge");
            model.put("description", "超级小爱对话引擎 (model 参数仅为名称, 不透传给小爱)");
            data.put(model);

            resp.put("data", data);
            return resp;
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    public static JSONObject errorResponse(String msg) {
        return buildError(msg, "server_error", null);
    }

    /** 标准 OpenAI 错误对象 */
    public static JSONObject buildError(String message, String type, String code) {
        try {
            JSONObject r = new JSONObject();
            JSONObject e = new JSONObject();
            e.put("message", message != null ? message : "Internal Server Error");
            e.put("type", type != null ? type : "server_error");
            if (code != null) e.put("code", code);
            r.put("error", e);
            return r;
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    /** OpenAPI 3.0 文档 */
    public static JSONObject openapiDoc() {
        try {
            JSONObject doc = new JSONObject();
            doc.put("openapi", "3.0.0");
            JSONObject info = new JSONObject();
            info.put("title", "XiaoAiBridge");
            info.put("version", "5.3.1");
            info.put("description", "把小米超级小爱(com.miui.voiceassist)的 AI 能力暴露为 OpenAI 兼容 API");
            doc.put("info", info);

            JSONObject paths = new JSONObject();
            JSONObject chatPath = new JSONObject();
            JSONObject post = new JSONObject();
            post.put("operationId", "createChatCompletion");
            post.put("summary", "Create a chat completion");
            JSONObject reqBody = new JSONObject();
            JSONArray required = new JSONArray();
            required.put("messages");
            JSONObject schema = new JSONObject();
            schema.put("type", "object");
            JSONObject props = new JSONObject();
            JSONObject ms = new JSONObject();
            ms.put("type", "array");
            ms.put("items", new JSONObject().put("type", "object"));
            props.put("messages", ms);
            props.put("model", new JSONObject().put("type", "string"));
            props.put("stream", new JSONObject().put("type", "boolean"));
            schema.put("properties", props);
            schema.put("required", required);
            reqBody.put("content", new JSONObject().put("application/json", new JSONObject().put("schema", schema)));
            post.put("requestBody", reqBody);
            JSONObject resp200 = new JSONObject();
            resp200.put("description", "OK");
            resp200.put("content", new JSONObject().put("application/json", new JSONObject()));
            post.put("responses", new JSONObject().put("200", resp200));
            chatPath.put("post", post);
            paths.put("/v1/chat/completions", chatPath);
            doc.put("paths", paths);
            return doc;
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    /** 图片 URL/路径/dataURL → ImageContent JSON ({base64Data,mimeType}) */
    public static JSONObject imageToContent(String url) {
        if (url == null) return null;
        String u = url.trim();
        if (u.isEmpty()) return null;
        byte[] bytes = null;
        String mime = "image/jpeg";
        try {
            if (u.startsWith("data:")) {
                int idx = u.indexOf(",");
                if (idx > 0) {
                    String meta = u.substring(5, idx);
                    if (meta.contains("png")) mime = "image/png";
                    else if (meta.contains("gif")) mime = "image/gif";
                    else if (meta.contains("webp")) mime = "image/webp";
                    bytes = java.util.Base64.getDecoder().decode(u.substring(idx + 1));
                }
            } else if (u.startsWith("http")) {
                java.net.HttpURLConnection conn = (java.net.HttpURLConnection) new java.net.URL(u).openConnection();
                conn.setConnectTimeout(10000);
                conn.setReadTimeout(20000);
                java.io.InputStream is = conn.getInputStream();
                java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = is.read(buf)) >= 0) baos.write(buf, 0, n);
                is.close();
                bytes = baos.toByteArray();
            } else {
                String fp = u;
                if (fp.startsWith("file://")) fp = fp.substring(7);
                java.io.File f = new java.io.File(fp);
                if (f.exists()) {
                    java.io.FileInputStream fis = new java.io.FileInputStream(f);
                    java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
                    byte[] buf = new byte[8192];
                    int n;
                    while ((n = fis.read(buf)) >= 0) baos.write(buf, 0, n);
                    fis.close();
                    bytes = baos.toByteArray();
                }
            }
        } catch (Exception e) {
            Logger.e("imageToContent: " + e.getMessage());
        }
        if (bytes == null || bytes.length == 0) return null;
        try {
            JSONObject obj = new JSONObject();
            obj.put("base64Data", java.util.Base64.getEncoder().encodeToString(bytes));
            obj.put("mimeType", mime);
            return obj;
        } catch (Exception e) {
            return null;
        }
    }

    // === Function Calling 支持 (v5.3.0) ===

    private static final String TOOLCALL_TAG_OPEN = "<toolcall>";
    private static final String TOOLCALL_TAG_CLOSE = "</toolcall>";

    /**
     * 从 AI 回复中解析 tool_calls。
     * 格式: <toolcall>[{"name":"fn","arguments":{...}}]</toolcall>
     * 返回 null 表示不含 tool_call。
     */
    public static java.util.List<JSONObject> parseToolCalls(String reply) {
        if (reply == null) return null;
        int start = reply.indexOf(TOOLCALL_TAG_OPEN);
        int end = reply.indexOf(TOOLCALL_TAG_CLOSE, start + TOOLCALL_TAG_OPEN.length());
        if (start < 0 || end < 0) return null;

        String jsonStr = reply.substring(start + TOOLCALL_TAG_OPEN.length(), end).trim();
        try {
            JSONArray arr = new JSONArray(jsonStr);
            java.util.List<JSONObject> calls = new java.util.ArrayList<>();
            long ts = System.currentTimeMillis();
            for (int i = 0; i < arr.length(); i++) {
                JSONObject tc = arr.optJSONObject(i);
                if (tc == null) continue;
                JSONObject call = new JSONObject();
                call.put("id", "call_" + ts + "_" + i);
                call.put("type", "function");
                JSONObject fn = new JSONObject();
                fn.put("name", tc.optString("name", ""));
                // arguments 必须是 string
                Object args = tc.opt("arguments");
                if (args instanceof JSONObject) {
                    fn.put("arguments", args.toString());
                } else {
                    fn.put("arguments", args != null ? args.toString() : "{}");
                }
                call.put("function", fn);
                calls.add(call);
            }
            return calls.isEmpty() ? null : calls;
        } catch (Exception e) {
            Logger.e("parseToolCalls: " + e.getMessage());
            return null;
        }
    }

    /** 从 AI 回复中提取 toolcall 标签之外的纯文本 content */
    public static String extractContentWithoutToolCalls(String reply) {
        if (reply == null) return "";
        int start = reply.indexOf(TOOLCALL_TAG_OPEN);
        if (start < 0) return reply;
        // 取标签前的文本
        String before = reply.substring(0, start).trim();
        // 取标签后的文本
        int end = reply.indexOf(TOOLCALL_TAG_CLOSE, start + TOOLCALL_TAG_OPEN.length());
        String after = (end >= 0) ? reply.substring(end + TOOLCALL_TAG_CLOSE.length()).trim() : "";
        String combined = (before + " " + after).trim();
        return combined.isEmpty() ? null : combined;
    }

    /** 构建含 tool_calls 的非流式 OpenAI 响应 */
    public static JSONObject buildToolCallResponse(String model,
            java.util.List<JSONObject> toolCalls, String content) {
        try {
            JSONObject resp = new JSONObject();
            resp.put("id", "chatcmpl-" + System.currentTimeMillis());
            resp.put("object", "chat.completion");
            resp.put("created", System.currentTimeMillis() / 1000);
            resp.put("model", model);

            JSONArray choices = new JSONArray();
            JSONObject choice = new JSONObject();
            choice.put("index", 0);

            JSONObject msg = new JSONObject();
            msg.put("role", "assistant");
            msg.put("content", content); // null 或残余文本
            JSONArray tcArr = new JSONArray();
            for (JSONObject tc : toolCalls) tcArr.put(tc);
            msg.put("tool_calls", tcArr);
            choice.put("message", msg);
            choice.put("finish_reason", "tool_calls");
            choices.put(choice);
            resp.put("choices", choices);

            JSONObject usage = new JSONObject();
            usage.put("prompt_tokens", 0);
            usage.put("completion_tokens", 0);
            usage.put("total_tokens", 0);
            resp.put("usage", usage);
            return resp;
        } catch (Exception e) {
            return errorResponse("Failed to build tool_call response: " + e.getMessage());
        }
    }

    /** 构建含 tool_calls 的流式 SSE chunk */
    public static String buildToolCallStreamChunk(String model,
            java.util.List<JSONObject> toolCalls) {
        try {
            JSONObject chunk = new JSONObject();
            chunk.put("id", "chatcmpl-" + System.currentTimeMillis());
            chunk.put("object", "chat.completion.chunk");
            chunk.put("created", System.currentTimeMillis() / 1000);
            chunk.put("model", model);

            JSONArray choices = new JSONArray();
            JSONObject choice = new JSONObject();
            choice.put("index", 0);

            JSONObject delta = new JSONObject();
            delta.put("role", "assistant");
            delta.put("content", JSONObject.NULL);
            JSONArray tcArr = new JSONArray();
            for (JSONObject tc : toolCalls) tcArr.put(tc);
            delta.put("tool_calls", tcArr);
            choice.put("delta", delta);
            choice.put("finish_reason", "tool_calls");

            choices.put(choice);
            chunk.put("choices", choices);
            return "data: " + chunk.toString() + "\n\n";
        } catch (Exception e) {
            return "";
        }
    }

    /**
     * 将 tools 定义转为 prompt 注入文本。
     * 格式:
     * [可用工具]
     * 1. name — description
     *    参数: schema
     * [/可用工具]
     * 调用工具时输出: <toolcall>[{"name":"...","arguments":{...}}]</toolcall>
     */
    public static String buildToolsPrompt(JSONArray tools, Object toolChoice) {
        if (tools == null || tools.length() == 0) return null;

        StringBuilder sb = new StringBuilder();
        sb.append("[可用工具]\n");
        for (int i = 0; i < tools.length(); i++) {
            JSONObject tool = tools.optJSONObject(i);
            if (tool == null) continue;
            JSONObject fn = tool.optJSONObject("function");
            if (fn == null) continue;
            String name = fn.optString("name", "");
            String desc = fn.optString("description", "");
            sb.append(i + 1).append(". ").append(name);
            if (!desc.isEmpty()) sb.append(" — ").append(desc);
            // 参数 schema
            JSONObject params = fn.optJSONObject("parameters");
            if (params != null) {
                sb.append("\n   参数: ").append(params.toString());
            }
            sb.append("\n");
        }
        sb.append("[/可用工具]\n\n");
        sb.append("如果需要调用工具，输出：\n");
        sb.append(TOOLCALL_TAG_OPEN).append("[{\"name\":\"函数名\",\"arguments\":{...}}]").append(TOOLCALL_TAG_CLOSE).append("\n\n");

        // tool_choice 处理
        if (toolChoice != null) {
            String tcStr = toolChoice.toString();
            if ("\"none\"".equals(tcStr)) {
                sb.append("【约束】不要调用任何工具，直接用自然语言回答。\n");
            } else if ("\"required\"".equals(tcStr)) {
                sb.append("【约束】你必须调用至少一个工具来回答。\n");
            } else if (toolChoice instanceof JSONObject) {
                JSONObject tcObj = (JSONObject) toolChoice;
                JSONObject fnChoice = tcObj.optJSONObject("function");
                if (fnChoice != null) {
                    String fnName = fnChoice.optString("name", "");
                    if (!fnName.isEmpty()) {
                        sb.append("【约束】你必须调用 ").append(fnName).append(" 工具。\n");
                    }
                }
            }
            // "auto" 不附加约束
        }

        sb.append("不需要调用工具时直接用自然语言回答用户问题。");
        return sb.toString();
    }

    /** JSON 模式兜底: 从回答中提取合法 JSON (或包装) */
    public static String extractJson(String s) {
        if (s == null) return null;
        String t = s.trim();
        if (t.isEmpty()) return null;
        try { new JSONObject(t); return t; } catch (Exception ignored) {}
        int i1 = t.indexOf("```json");
        if (i1 >= 0) {
            int i2 = t.indexOf("```", i1 + 7);
            if (i2 > i1) {
                String frag = t.substring(i1 + 7, i2).trim();
                try { new JSONObject(frag); return frag; } catch (Exception ignored) {}
            }
        }
        i1 = t.indexOf("{");
        int i2 = t.lastIndexOf("}");
        if (i1 >= 0 && i2 > i1) {
            String frag = t.substring(i1, i2 + 1);
            try { new JSONObject(frag); return frag; } catch (Exception ignored) {}
        }
        try {
            JSONObject obj = new JSONObject();
            obj.put("reply", s);
            return obj.toString();
        } catch (Exception e) {
            return null;
        }
    }
}
