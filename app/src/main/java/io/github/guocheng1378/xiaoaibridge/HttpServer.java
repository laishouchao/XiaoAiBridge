package io.github.guocheng1378.xiaoaibridge;

import android.content.Context;
import org.json.JSONObject;
import org.json.JSONArray;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 极简 HTTP 服务器 (0.0.0.0)
 * 提供 OpenAI 兼容 API: /v1/chat/completions (流式+非流式) /v1/models /health
 */
public class HttpServer {

    private final Context context;
    private final CliClient cli;
    private static final boolean CORS = true;

    private final java.util.List<JSONObject> reqLog = new java.util.ArrayList<>();
    private int reqCount = 0;
    private long reqWindowStart = System.currentTimeMillis();

    public HttpServer(Context context) {
        this.context = context;
        this.cli = new CliClient(context);
    }

    public void start() {
        new Thread(() -> {
            try {
                Config.activeSocket = "voiceassist-internal";
                Logger.d("Mode: voiceassist (AiClientHook)");
            } catch (Exception e) {
                Logger.e("Init error: " + e.getMessage());
            }
        }).start();

        new Thread(() -> {
            try {
                int port = Config.HTTP_PORT;
                ServerSocket serverSocket = null;
                for (int attempt = 0; attempt < 5; attempt++) {
                    try {
                        serverSocket = new ServerSocket();
                        serverSocket.setReuseAddress(true);
                        serverSocket.bind(new InetSocketAddress("0.0.0.0", port));
                        break;
                    } catch (Exception e) {
                        Logger.d("Port " + port + " busy, trying " + (port + 1));
                        port++;
                        serverSocket = null;
                    }
                }
                if (serverSocket == null) {
                    Logger.e("HTTP server failed: no free port");
                    return;
                }
                Config.HTTP_PORT = port;
                ExecutorService executor = Executors.newFixedThreadPool(Config.THREAD_POOL_SIZE);
                Logger.d("HTTP listening on 0.0.0.0:" + port);
                while (true) {
                    Socket client = serverSocket.accept();
                    executor.submit(() -> handleClient(client));
                }
            } catch (Exception e) {
                Logger.e("HTTP server failed: " + e.getMessage(), e);
            }
        }).start();
    }

    private void handleClient(Socket client) {
        try {
            client.setSoTimeout(60000);
            InputStream is = client.getInputStream();
            OutputStream os = client.getOutputStream();

            String requestLine = readHttpLine(is);
            if (requestLine == null) { client.close(); return; }

            long reqStart = System.currentTimeMillis();
            String[] parts = requestLine.split(" ");
            String method = parts[0];
            String target = parts.length > 1 ? parts[1] : "/";

            String apiKey = "";
            int contentLength = 0;
            while (true) {
                String line = readHttpLine(is);
                if (line == null || line.isEmpty()) break;
                int idx = line.indexOf(":");
                if (idx > 0) {
                    String name = line.substring(0, idx).trim().toLowerCase();
                    String value = line.substring(idx + 1).trim();
                    if ("x-api-key".equals(name)) apiKey = value;
                    if ("authorization".equals(name) && value.startsWith("Bearer "))
                        apiKey = value.substring(7).trim();
                    if ("content-length".equals(name)) {
                        try { contentLength = Integer.parseInt(value); }
                        catch (Exception e) {}
                    }
                }
            }

            String body = "";
            if (contentLength > 0) {
                byte[] bytes = new byte[contentLength];
                int off = 0;
                while (off < contentLength) {
                    int r = is.read(bytes, off, contentLength - off);
                    if (r < 0) break;
                    off += r;
                }
                body = new String(bytes, 0, off, "UTF-8");
            }

            String path = target;
            int qidx = target.indexOf("?");
            if (qidx >= 0) path = target.substring(0, qidx);

            // 鉴权 (OPTIONS 预检跳过)
            if (!"OPTIONS".equals(method)
                && Config.API_TOKEN.length() > 0
                && !Config.API_TOKEN.equals(apiKey)) {
                sendResponse(os, 401, OpenAiCompat.buildError(
                    "Invalid API key provided", "invalid_request_error", "invalid_api_key").toString());
                client.close(); return;
            }

            // 限流 (RATE_LIMIT=0 关闭)
            if (Config.RATE_LIMIT > 0) {
                long now = System.currentTimeMillis();
                if (now - reqWindowStart > 60000) { reqWindowStart = now; reqCount = 0; }
                reqCount++;
                if (reqCount > Config.RATE_LIMIT) {
                    sendResponse(os, 429, OpenAiCompat.buildError(
                        "Too Many Requests", "rate_limit_error", "rate_limited").toString());
                    client.close(); return;
                }
            }

            if ("OPTIONS".equals(method)) {
                sendResponse(os, 200, "{}");
            } else if ("/".equals(path)) {
                JSONObject r = new JSONObject();
                r.put("name", "XiaoAiApiBridge");
                r.put("version", "5.1.1");
                r.put("docs", "/openapi.json");
                r.put("models", "/v1/models");
                sendResponse(os, 200, r.toString());
            } else if ("/health".equals(path)) {
                JSONObject r = new JSONObject();
                r.put("status", "ok");
                r.put("model", Config.MODEL_NAME);
                r.put("socket", Config.activeSocket);
                sendResponse(os, 200, r.toString());
            } else if ("/openapi.json".equals(path)) {
                sendResponse(os, 200, OpenAiCompat.openapiDoc().toString());
            } else if ("/v1/models".equals(path)
                       && ("GET".equals(method) || "POST".equals(method))) {
                sendResponse(os, 200, OpenAiCompat.buildModelList().toString());
            } else if ("/v1/admin/status".equals(path) && "GET".equals(method)) {
                JSONObject st = new JSONObject();
                st.put("status", "ok");
                st.put("version", "5.1.1");
                st.put("model", Config.MODEL_NAME);
                st.put("socket", Config.activeSocket);
                st.put("routes", 0);
                sendResponse(os, 200, st.toString());
            } else if ("/v1/tools".equals(path) && "GET".equals(method)) {
                JSONObject tr = new JSONObject();
                tr.put("object", "list");
                tr.put("note", "超级小爱内置工具由它自动调用");
                sendResponse(os, 200, tr.toString());
            } else if ("/v1/chat/completions".equals(path) && "POST".equals(method)) {
                handleChatCompletions(os, body);
            } else if ("/v1/chat".equals(path) && "POST".equals(method)) {
                handleV1Chat(os, body);
            } else if ("/v1/chat/reset".equals(path) && "POST".equals(method)) {
                JSONObject reqObj = new JSONObject(body);
                String chatId = reqObj.has("chat_id") ? reqObj.optString("chat_id")
                        : (reqObj.has("user") ? reqObj.optString("user") : Config.API_CHAT_ID);
                if (chatId.isEmpty()) chatId = Config.API_CHAT_ID;
                JSONObject resp = new JSONObject();
                resp.put("ok", true);
                resp.put("chat_id", chatId);
                resp.put("cleared", "local");
                resp.put("note", "会话由超级小爱 App 自身管理");
                sendResponse(os, 200, resp.toString());
            } else if ("/v1/admin/logs".equals(path) && "GET".equals(method)) {
                JSONObject lr = new JSONObject();
                lr.put("count", reqLog.size());
                lr.put("logs", new JSONArray(reqLog));
                sendResponse(os, 200, lr.toString());
            } else if ("/v1/admin/reload".equals(path) && "GET".equals(method)) {
                Config.loadFrom(context.getApplicationContext());
                JSONObject rr = new JSONObject();
                rr.put("ok", true);
                rr.put("port", Config.HTTP_PORT);
                sendResponse(os, 200, rr.toString());
            } else {
                sendResponse(os, 404, OpenAiCompat.buildError(
                    "Not Found", "invalid_request_error", "not_found").toString());
            }

            // 请求日志
            if (Config.REQ_LOGGING) {
                try {
                    JSONObject logEntry = new JSONObject();
                    logEntry.put("t", System.currentTimeMillis() / 1000);
                    logEntry.put("method", method);
                    logEntry.put("path", path);
                    logEntry.put("ms", System.currentTimeMillis() - reqStart);
                    reqLog.add(logEntry);
                    if (reqLog.size() > 100) reqLog.remove(0);
                } catch (Exception ignored) {}
            }

            client.close();
        } catch (Exception e) {
            Logger.e("Handler error: " + e.getMessage());
            try { client.close(); } catch (Exception ignored) {}
        }
    }

    private CliClient.CliResult chatWithRetry(String text, String chatId, String agentId,
            CliClient.TextSink sink, org.json.JSONArray images) {
        CliClient.CliResult r = AiClientHook.chat(text, chatId, agentId, sink, images);
        if (Config.RETRY && r != null && r.error != null) {
            Logger.d("Chat failed (" + r.error + "), retrying once...");
            try { Thread.sleep(300); } catch (Exception ignored) {}
            r = AiClientHook.chat(text, chatId, agentId, sink, images);
        }
        return r;
    }

    private void handleV1Chat(OutputStream os, String body) throws Exception {
        JSONObject reqObj = new JSONObject(body);
        String text = reqObj.optString("text", "");
        String chatId = reqObj.has("chatId") ? reqObj.optString("chatId") : null;
        String agentId = reqObj.has("agentId") ? reqObj.optString("agentId") : null;

        if (text.length() == 0) {
            sendResponse(os, 400, OpenAiCompat.buildError(
                "missing 'text'", "invalid_request_error", "missing_text").toString());
            return;
        }

        CliClient.CliResult r = chatWithRetry(text, chatId, agentId, null, null);
        JSONObject resp = new JSONObject();
        resp.put("ok", r.error == null);
        resp.put("reply", r.reply);
        if (r.error != null) resp.put("error", r.error);
        if (r.chatId != null) resp.put("chatId", r.chatId);
        resp.put("frames", r.frames);
        sendResponse(os, 200, resp.toString());
    }

    private void handleChatCompletions(OutputStream os, String body) throws Exception {
        JSONObject reqObj = new JSONObject(body);
        boolean stream = reqObj.optBoolean("stream", false);
        String model = reqObj.optString("model", Config.MODEL_NAME);
        if (model == null || model.isEmpty()) model = Config.MODEL_NAME;
        JSONArray messages = reqObj.optJSONArray("messages");

        // 拼接 messages (system + history + user) - 支持多模态图片
        JSONArray imagesArr = new JSONArray();
        StringBuilder sb = new StringBuilder();

        if (messages != null) {
            for (int i = 0; i < messages.length(); i++) {
                JSONObject m = messages.optJSONObject(i);
                if (m == null) continue;
                String role = m.optString("role", "user");
                Object c = m.opt("content");
                String cStr = "";
                if (c instanceof String) {
                    cStr = c.toString();
                } else if (c instanceof JSONArray) {
                    JSONArray carr = (JSONArray) c;
                    for (int j = 0; j < carr.length(); j++) {
                        JSONObject part = carr.optJSONObject(j);
                        if (part == null) continue;
                        String ptype = part.optString("type", "");
                        if ("text".equals(ptype)) {
                            if (!cStr.isEmpty()) cStr += "\n";
                            cStr += part.optString("text", "");
                        } else if ("image_url".equals(ptype)) {
                            JSONObject iu = part.optJSONObject("image_url");
                            String iurl = iu != null ? iu.optString("url", "") : part.optString("image_url", "");
                            if (!iurl.isEmpty()) {
                                JSONObject ic = OpenAiCompat.imageToContent(iurl);
                                if (ic != null) imagesArr.put(ic);
                            }
                        }
                    }
                } else {
                    cStr = m.optString("content", "");
                }
                if (cStr.isEmpty()) continue;
                if ("system".equals(role)) sb.append("系统设定：").append(cStr).append("\n");
                else if ("assistant".equals(role)) sb.append(cStr).append("\n");
                else if ("tool".equals(role)) sb.append("[工具结果] ").append(cStr).append(" [/工具结果]\n");
                else sb.append(cStr).append("\n");
            }
        }

        // JSON 模式 + 参数映射
        boolean jsonMode = false;
        JSONObject rf = reqObj.optJSONObject("response_format");
        if (rf != null && "json_object".equals(rf.optString("type", ""))) {
            jsonMode = true;
            sb.append("严格要求：你必须只输出一个合法 JSON 对象。禁止使用 Markdown、列表符号、解释文字。示例格式：{\"key\":\"value\"}\n");
        }
        double temp = reqObj.optDouble("temperature", -1);
        if (temp >= 0) {
            if (temp < 0.5) sb.append("回答要求：严谨、准确、简洁、事实导向，避免发散。\n");
            else if (temp > 1.2) sb.append("回答要求：有创意、发散、生动，可以适当发挥。\n");
        }
        int maxTok = reqObj.optInt("max_tokens", -1);
        if (maxTok > 0) {
            if (maxTok < 100) sb.append("回答要求：非常简短，一句话以内。\n");
            else if (maxTok < 300) sb.append("回答要求：简洁，控制在几行内。\n");
            else if (maxTok > 2000) sb.append("回答要求：详细完整，尽量展开论述，条理清晰。\n");
        }

        String text = sb.toString().trim();
        JSONArray useImages = imagesArr.length() > 0 ? imagesArr : null;
        if (text.isEmpty()) {
            sendResponse(os, 400, OpenAiCompat.buildError(
                "messages is required", "invalid_request_error", "missing_messages").toString());
            return;
        }

        // model 参数仅为名称, 底层始终为同一超级小爱引擎 (agentId 在 Hook 层未使用)
        String agentId = Config.defaultAgentId;

        if (stream) {
            // SSE 流式
            StringBuilder sbHeader = new StringBuilder();
            sbHeader.append("HTTP/1.1 200 OK\r\n");
            if (CORS) sbHeader.append("Access-Control-Allow-Origin: *\r\n");
            sbHeader.append("Content-Type: text/event-stream; charset=utf-8\r\n");
            sbHeader.append("Cache-Control: no-cache\r\nConnection: close\r\n\r\n");
            os.write(sbHeader.toString().getBytes("UTF-8"));
            os.flush();

            CliClient.CliResult r = chatWithRetry(text, Config.API_CHAT_ID, agentId, t -> {
                try {
                    os.write(OpenAiCompat.buildStreamChunk(model, t, null).getBytes("UTF-8"));
                    os.flush();
                } catch (Exception ignored) {}
            }, useImages);

            os.write(OpenAiCompat.buildStreamChunk(model, null, "stop").getBytes("UTF-8"));
            os.write("data: [DONE]\n\n".getBytes("UTF-8"));
            os.flush();
            return;
        }

        CliClient.CliResult r = chatWithRetry(text, Config.API_CHAT_ID, agentId, null, useImages);
        if (r.error != null) {
            sendResponse(os, 500, OpenAiCompat.buildError(
                r.error, "server_error", "upstream_error").toString());
            return;
        }
        String reply = r.reply;
        if (jsonMode) {
            String j = OpenAiCompat.extractJson(reply);
            reply = j != null ? j : "{}";
        }
        sendResponse(os, 200, OpenAiCompat.buildSyncResponse(model, reply).toString());
    }

    private String readHttpLine(InputStream is) throws Exception {
        StringBuilder sb = new StringBuilder();
        while (true) {
            int b = is.read();
            if (b < 0) return null;
            if (b == 10) break;
            if (b != 13) sb.append((char) b);
        }
        return sb.toString();
    }

    private void sendResponse(OutputStream os, int code, String body) throws Exception {
        String reason = "OK";
        if (code == 400) reason = "Bad Request";
        if (code == 401) reason = "Unauthorized";
        if (code == 404) reason = "Not Found";
        if (code == 429) reason = "Too Many Requests";
        if (code == 500) reason = "Internal Server Error";
        byte[] bytes = body.getBytes("UTF-8");
        StringBuilder header = new StringBuilder();
        header.append("HTTP/1.1 ").append(code).append(" ").append(reason).append("\r\n");
        if (CORS) {
            header.append("Access-Control-Allow-Origin: *\r\n");
            header.append("Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n");
            header.append("Access-Control-Allow-Headers: Content-Type, Authorization, X-Api-Key\r\n");
        }
        header.append("Content-Type: application/json; charset=utf-8\r\n");
        header.append("Content-Length: ").append(bytes.length).append("\r\n");
        header.append("Connection: close\r\n\r\n");
        os.write(header.toString().getBytes("UTF-8"));
        os.write(bytes);
        os.flush();
    }
}
