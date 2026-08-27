package io.github.guocheng1378.xiaoaibridge;

import android.content.Context;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.json.JSONObject;
import org.json.JSONArray;

/**
 * v3.4 Channel 方案: 通过 com.xiaomi.ai.core.b (Channel) 发送 Nlp.Request 事件
 *
 * 关键修正 (v3.4):
 * - 使用 Nlp.Request (implements EventPayload) 替代 Nlp.ExecuteQuery (implements InstructionPayload)
 * - 使用 APIUtils.buildEvent(request) 构造 Event (正确处理 @NamespaceName 注解)
 * - 发送前检查 Channel.isConnected()
 * - Hook onConnected/onDisconnected 跟踪连接状态
 *
 * 核心机制:
 * 1. setChannel() - 从 Hook 捕获 Channel 实例 (com.xiaomi.ai.core.b)
 * 2. onConnectionState() - 从 Hook 跟踪连接状态
 * 3. captureEventTemplate() - 从 postEvent/buildEvent 捕获真实 Event 模板
 * 4. chat() - 构造 Nlp.Request -> APIUtils.buildEvent -> channel.postEvent(event)
 * 5. onInstructionJson() - 从 InstructionWrapper JSON 解析回复 (SpeechSynthesizer.Speak.text)
 *
 * 类映射:
 *   Channel = com.xiaomi.ai.core.b (混淆)
 *   ChannelListener = com.xiaomi.ai.core.c (混淆)
 *   InstructionWrapper = com.xiaomi.ai.core.e (混淆)
 *   Event = com.xiaomi.ai.api.common.Event (未混淆)
 *   EventHeader = com.xiaomi.ai.api.common.EventHeader (未混淆)
 *   APIUtils = com.xiaomi.ai.api.common.APIUtils (未混淆)
 *   Nlp.Request = com.xiaomi.ai.api.Nlp$Request (未混淆, implements EventPayload)
 */
public class AiClientHook {

    public interface TextSink {
        void onDelta(String text);
    }

    // 混淆类名
    private static final String CLS_CHANNEL_WRAPPER = "com.xiaomi.ai.core.b"; // b 是包装类, 内部有 Channel f40199a
    private static final String CLS_CHANNEL = "com.xiaomi.ai.core.Channel";    // 真正的抽象 Channel
    // API 类名 (未混淆)
    private static final String CLS_EVENT = "com.xiaomi.ai.api.common.Event";
    private static final String CLS_EVENT_HEADER = "com.xiaomi.ai.api.common.EventHeader";
    private static final String CLS_MESSAGE = "com.xiaomi.ai.api.common.Message";
    private static final String CLS_API_UTILS = "com.xiaomi.ai.api.common.APIUtils";
    private static final String CLS_EVENT_PAYLOAD = "com.xiaomi.ai.api.common.EventPayload";
    private static final String CLS_NLP_REQUEST = "com.xiaomi.ai.api.Nlp$Request";
    private static final String CLS_POSTBACK_REQUEST = "com.xiaomi.ai.api.Nlp$PostBackRequest";
    private static final String CLS_LLM_REQUEST = "com.xiaomi.ai.api.Nlp$RequestLargeLanguageModelContent";
    private static final String CLS_CR0_G = "cr0.g";

    // 状态
    private static volatile Object capturedChannel = null;
    private static volatile Object capturedChannelListener = null;
    private static volatile boolean channelConnected = false;
    private static volatile Object eventTemplate = null;
    private static volatile Object nlpRequestTemplate = null;
    private static volatile boolean templateDiagnosed = false;

    // === 由 HookEntry 调用的回调 ===

    public static void setChannel(Object channel) {
        if (channel != null) {
            capturedChannel = channel;
            Logger.d("AiClientHook: captured Channel: " + channel.getClass().getName());
            checkConnectionState();
        }
    }

    public static void setChannelListener(Object listener) {
        if (listener != null && capturedChannelListener == null) {
            capturedChannelListener = listener;
            Logger.d("AiClientHook: captured ChannelListener: " + listener.getClass().getName());
        }
    }

    public static void onConnectionState(boolean connected) {
        if (connected != channelConnected) {
            channelConnected = connected;
            Logger.d("AiClientHook: connection state: " + connected);
        }
    }

    public static void captureEventTemplate(Object event) {
        if (event != null) {
            eventTemplate = event;
            if (!templateDiagnosed) {
                templateDiagnosed = true;
                diagnoseEvent(event);
            }
        }
    }

    public static void captureNlpRequest(Object request) {
        if (request != null) {
            nlpRequestTemplate = request;
            Logger.d("AiClientHook: captured Nlp.Request template: " + request.getClass().getName());
        }
    }

    /**
     * 从 b 包装类提取真正的 Channel 对象
     * com.xiaomi.ai.core.b 有 Channel f40199a 字段
     */
    private static Object getRealChannel(Object wrapper) {
        if (wrapper == null) return null;
        try {
            String wrapperClassName = wrapper.getClass().getName();

            // v4.1: com.xiaomi.ai.core.b 本身就是 Channel (混淆后), 直接返回
            if (CLS_CHANNEL_WRAPPER.equals(wrapperClassName)) {
                Logger.d("AiClientHook: wrapper is Channel (b), using directly");
                return wrapper;
            }

            // 如果已经是 Channel 子类, 直接返回
            Class<?> channelClass = null;
            try {
                channelClass = Class.forName(CLS_CHANNEL, false, wrapper.getClass().getClassLoader());
            } catch (ClassNotFoundException ignored) {}
            if (channelClass != null && channelClass.isAssignableFrom(wrapper.getClass())) {
                Logger.d("AiClientHook: object is already a Channel");
                return wrapper;
            }

            // 从包装类提取 Channel 字段 (优先找 Channel 类型, 跳过 ChannelListener 类型)
            Class<?> cls = wrapper.getClass();
            while (cls != null && cls != Object.class) {
                for (java.lang.reflect.Field f : cls.getDeclaredFields()) {
                    String fieldTypeName = f.getType().getName();
                    // v4.1: 跳过 ChannelListener (c) 字段, 避免错误提取
                    if (fieldTypeName.contains("ChannelListener") || fieldTypeName.endsWith(".c")) {
                        continue;
                    }
                    if (channelClass != null && channelClass.isAssignableFrom(f.getType())) {
                        f.setAccessible(true);
                        Object channel = f.get(wrapper);
                        if (channel != null) {
                            Logger.d("AiClientHook: extracted real Channel from " + cls.getSimpleName()
                                + "." + f.getName() + " -> " + channel.getClass().getName());
                            return channel;
                        }
                    }
                }
                cls = cls.getSuperclass();
            }

            // v4.1: 不递归搜索字段, 避免误提取 ChannelListener
            Logger.d("AiClientHook: could not extract Channel from " + wrapperClassName + ", using wrapper");
            return wrapper;
        } catch (Exception e) {
            Logger.d("AiClientHook: getRealChannel error: " + e.getMessage());
        }
        return wrapper;
    }

    /** 检查 Channel 连接状态 (方法名混淆, 先查字段再查方法) */
    private static void checkConnectionState() {
        Object channel = capturedChannel;
        if (channel == null) return;
        try {
            boolean result = isChannelConnected();
            onConnectionState(result);
            Logger.d("AiClientHook: checkConnectionState result=" + result);
        } catch (Exception e) {
            Logger.d("AiClientHook: checkConnectionState failed: " + e.getMessage());
        }
    }

    /** 通过反射检查 Channel 是否已连接 (优先查字段, 再查方法, 最后默认 true) */
    private static boolean isChannelConnected() {
        Object channel = capturedChannel;
        if (channel == null) return false;
        try {
            Class<?> cls = channel.getClass();

            // 方式1: 查找 boolean 字段 (connected, mConnected, mIsConnected, isConnected)
            while (cls != null && cls != Object.class) {
                for (Field f : cls.getDeclaredFields()) {
                    String fn = f.getName().toLowerCase();
                    if (f.getType() == boolean.class || f.getType() == Boolean.class) {
                        if (fn.contains("connect") || fn.contains("active") || fn.contains("open")
                            || fn.contains("alive") || fn.contains("ready")) {
                            f.setAccessible(true);
                            Object val = f.get(channel);
                            boolean connected = val instanceof Boolean ? (Boolean) val : false;
                            Logger.d("AiClientHook: isChannelConnected via field " + f.getName() + "=" + connected);
                            return connected;
                        }
                    }
                }
                cls = cls.getSuperclass();
            }

            // 方式2: 查找 boolean() 方法
            cls = channel.getClass();
            Method isConnected = findMethodBySignature(cls, boolean.class, 0);
            if (isConnected != null) {
                isConnected.setAccessible(true);
                boolean result = (boolean) isConnected.invoke(channel);
                Logger.d("AiClientHook: isChannelConnected via method " + isConnected.getName() + "=" + result);
                return result;
            }

            // 方式3: 查找 boolean isXxx() 方法 (常见命名: isConnected, isOpen, isAlive, isReady)
            while (cls != null && cls != Object.class) {
                for (Method m : cls.getDeclaredMethods()) {
                    if (m.getReturnType() == boolean.class && m.getParameterCount() == 0) {
                        String mn = m.getName().toLowerCase();
                        if (mn.startsWith("is") && (mn.contains("connect") || mn.contains("active")
                            || mn.contains("open") || mn.contains("alive") || mn.contains("ready"))) {
                            m.setAccessible(true);
                            boolean result = (boolean) m.invoke(channel);
                            Logger.d("AiClientHook: isChannelConnected via " + m.getName() + "=" + result);
                            return result;
                        }
                    }
                }
                cls = cls.getSuperclass();
            }

            Logger.d("AiClientHook: isChannelConnected: no method/field found, assuming connected=true");
            return true;  // 找不到状态检测方法时, 乐观假设已连接

        } catch (Exception e) {
            Logger.d("AiClientHook: isChannelConnected failed: " + e.getMessage());
            return true;  // 异常时也乐观假设已连接
        }
    }

    // === 诊断 ===

    private static void diagnoseEvent(Object event) {
        try {
            Logger.d("AiClientHook: === Event Template Diagnosis ===");
            Logger.d("AiClientHook: Event class: " + event.getClass().getName());
            Logger.d("AiClientHook: Event toString: " + truncate(event.toString(), 500));

            Class<?> cls = event.getClass();
            while (cls != null && cls != Object.class) {
                for (Field f : cls.getDeclaredFields()) {
                    if (f.getName().startsWith("access$") || f.getName().startsWith("$")) continue;
                    f.setAccessible(true);
                    Object val = f.get(event);
                    String valStr = val != null ? truncate(val.toString(), 200) : "null";
                    Logger.d("AiClientHook: " + cls.getSimpleName() + "." + f.getName()
                        + " (" + f.getType().getSimpleName() + ") = " + valStr);

                    if (val != null) {
                        String fieldName = f.getName().toLowerCase();
                        if (fieldName.contains("header") || fieldName.contains("payload")) {
                            diagnoseSubObject(val, f.getName());
                        }
                    }
                }
                cls = cls.getSuperclass();
            }
            Logger.d("AiClientHook: === End Event Diagnosis ===");
        } catch (Exception e) {
            Logger.d("AiClientHook: diagnoseEvent error: " + e.getMessage());
        }
    }

    private static void diagnoseSubObject(Object obj, String label) {
        try {
            Class<?> cls = obj.getClass();
            while (cls != null && cls != Object.class) {
                for (Field f : cls.getDeclaredFields()) {
                    if (f.getName().startsWith("access$") || f.getName().startsWith("$")) continue;
                    f.setAccessible(true);
                    Object val = f.get(obj);
                    String valStr = val != null ? truncate(val.toString(), 150) : "null";
                    Logger.d("AiClientHook: " + label + "." + f.getName()
                        + " (" + f.getType().getSimpleName() + ") = " + valStr);
                }
                cls = cls.getSuperclass();
            }
        } catch (Exception e) {
            Logger.d("AiClientHook: diagnoseSubObject(" + label + ") error: " + e.getMessage());
        }
    }

    // === 响应同步 ===
    private static final Object lock = new Object();
    private static CountDownLatch responseLatch = null;
    private static String lastReply = null;
    private static String lastError = null;
    private static int lastFrames = 0;
    private static TextSink currentSink = null;
    private static boolean answerStarted = false;
    // v5.2.1: 当前请求的 event id, 即其响应事件的 dialog_id; null 表示未启用会话门控
    private static volatile String currentDialogId = null;

    private static String lastInstructionId = null;

    // 本轮回复的引用文献 (Template.LLMReferenceInfo → OpenAI url_citation)
    private static final java.util.List<org.json.JSONObject> lastAnnotations =
            new java.util.ArrayList<>();

    // === 响应处理 ===

    public static void onInstructionJson(String json) {
        try {
            JSONObject obj = new JSONObject(json);
            JSONObject header = obj.optJSONObject("header");
            if (header == null) return;

            String id = header.optString("id", "");
            String name = header.optString("name", "");
            String namespace = header.optString("namespace", "");
            JSONObject payload = obj.optJSONObject("payload");

            // v5.2.1: 会话门控 — 请求 event id 即响应 dialog_id, 只处理当前请求的事件。
            // 防止上一轮迟到的 Dialog.Finish 释放新一轮闩锁 (滚动摘要背靠背请求必现空回复)。
            // 置于去重之前, 避免旧会话事件污染 lastInstructionId 导致新事件被误去重。
            String dialogId = header.optString("dialog_id", "");
            if (currentDialogId != null && !dialogId.isEmpty()
                    && !dialogId.equals(currentDialogId)) {
                Logger.d("AiClientHook: session-gate REJECTED event=" + name + " dialog=" + dialogId + " current=" + currentDialogId);
                return;
            }

            // v4.0: 去重 - InstructionWrapper 构造器和 ChannelListener.onInstruction 都会触发
            if (id.equals(lastInstructionId) && !name.isEmpty()) return;
            lastInstructionId = id;

            if ("Dialog".equals(namespace) && "Finish".equals(name)) {
                onEnd();
                return;
            }

            if ("System".equals(namespace) && ("Ack".equals(name) || "Abort".equals(name))) return;
            if ("SpeechRecognizer".equals(namespace)) return;

            Logger.d("AiClientHook: Instruction: " + namespace + "." + name
                + " payload=" + (payload != null ? truncate(payload.toString(), 300) : "null"));

            if ("Nlp".equals(namespace)) {
                if ("StartAnswer".equals(name) || "StartStream".equals(name) || "StartPreStream".equals(name)) {
                    answerStarted = true;
                    return;
                }
                if ("FinishAnswer".equals(name) || "FinishStream".equals(name)) {
                    onEnd();
                    return;
                }
                // FinishPreStream 不是真正的结束, 只是预流结束, 回复在 ToastStream 中
                if ("FinishPreStream".equals(name)) return;
            }

            if ("SpeechSynthesizer".equals(namespace)) {
                if ("StartSpeakStream".equals(name)) {
                    answerStarted = true;
                    return;
                }
                if ("FinishSpeakStream".equals(name) || "FinishSpeak".equals(name)) {
                    onEnd();
                    return;
                }
                // Speak/SpeakStream 包含 TTS 文本, 与 ToastStream 重复, 跳过
                return;
            }

            // v4.0: 过滤非文本 UI 事件, 只保留真实的回复文本
            if ("Template".equals(namespace)) {
                if ("LLMLoadingCard".equals(name)) return;
                if ("FrontendPage".equals(name)) return;
                if ("ResultOperationInfo".equals(name)) return;
                // LLMReferenceInfo 是引用来源, 转成 annotations 而非正文
                if ("LLMReferenceInfo".equals(name)) {
                    collectReferenceInfo(payload);
                    return;
                }
                // ToastStream 有 markdown_text (真实回复)
                if ("Query".equals(name)) return; // 服务端回声, 不是回复
            }
            if ("Suggestion".equals(namespace)) return;
            if ("System".equals(namespace)) return;
            // v5.2.1: Settings 是连接握手事件 (ConnectionChallenge 等), 挑战码曾被兜底提取为正文
            if ("Settings".equals(namespace)) return;
            if ("Dialog".equals(namespace)) return;

            if (payload != null) {
                String text = extractTextFromPayload(payload, name, namespace);
                if (text != null && !text.isEmpty()) {
                    Logger.d("AiClientHook: extracted text: " + truncate(text, 200));
                    onResponse(text, false);
                }
            }
        } catch (Exception e) {
            Logger.d("AiClientHook: JSON parse error: " + e.getMessage());
        }
    }

    public static void onInstructionObject(Object instruction) {
        if (instruction == null) return;
        try {
            String str = instruction.toString();
            if (str.startsWith("{")) {
                onInstructionJson(str);
            }
        } catch (Exception ignored) {}
    }

    private static String extractTextFromPayload(JSONObject payload, String instructionName, String namespace) {
        // v3.6: Skip Dialog.Reject - it's not a text response, it's a rejection event
        if ("Dialog".equals(namespace) && "Reject".equals(instructionName)) return null;

        String text = payload.optString("text", null);
        if (text != null && !text.isEmpty()) return text;

        text = payload.optString("markdown_text", null);
        if (text != null && !text.isEmpty()) return text;

        text = payload.optString("answer", null);
        if (text != null && !text.isEmpty()) return text;

        text = payload.optString("content", null);
        if (text != null && !text.isEmpty()) return text;

        text = payload.optString("reply", null);
        if (text != null && !text.isEmpty()) return text;

        JSONObject data = payload.optJSONObject("data");
        if (data != null) {
            text = data.optString("text", null);
            if (text != null && !text.isEmpty()) return text;
            JSONObject tts = data.optJSONObject("tts");
            if (tts != null) {
                text = tts.optString("text", null);
                if (text != null && !text.isEmpty()) return text;
            }
        }

        JSONObject tts = payload.optJSONObject("tts");
        if (tts != null) {
            text = tts.optString("text", null);
            if (text != null && !text.isEmpty()) return text;
        }

        JSONArray results = payload.optJSONArray("results");
        if (results != null && results.length() > 0) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < results.length(); i++) {
                JSONObject r = results.optJSONObject(i);
                if (r != null) {
                    String t = r.optString("text", null);
                    if (t != null && !t.isEmpty()) sb.append(t);
                }
            }
            if (sb.length() > 0) return sb.toString();
        }

        text = payload.optString("delta", null);
        if (text != null && !text.isEmpty()) return text;

        return findFirstLongString(payload);
    }

    private static String findFirstLongString(JSONObject obj) {
        try {
            java.util.Iterator<String> keys = obj.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                Object val = obj.get(key);
                if (val instanceof String) {
                    String s = (String) val;
                    if (s.length() > 5) return s;
                } else if (val instanceof JSONObject) {
                    String found = findFirstLongString((JSONObject) val);
                    if (found != null) return found;
                } else if (val instanceof JSONArray) {
                    JSONArray arr = (JSONArray) val;
                    for (int i = 0; i < arr.length(); i++) {
                        Object item = arr.opt(i);
                        if (item instanceof String && ((String) item).length() > 5) {
                            return (String) item;
                        } else if (item instanceof JSONObject) {
                            String found = findFirstLongString((JSONObject) item);
                            if (found != null) return found;
                        }
                    }
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    /** Template.LLMReferenceInfo → OpenAI annotations (url_citation), 按 URL 去重 */
    private static void collectReferenceInfo(org.json.JSONObject payload) {
        try {
            if (payload == null) return;
            org.json.JSONArray items = payload.optJSONArray("items");
            if (items == null) return;
            synchronized (lock) {
                for (int i = 0; i < items.length(); i++) {
                    org.json.JSONObject item = items.optJSONObject(i);
                    if (item == null) continue;

                    String title = item.optString("content", "");
                    String url = "";
                    org.json.JSONObject icon = item.optJSONObject("skill_icon");
                    if (icon != null) {
                        if (title.isEmpty()) title = icon.optString("description", "");
                        // launcher.url 是引用页面的真实链接; sources 是图标图床
                        org.json.JSONObject launcher = icon.optJSONObject("launcher");
                        if (launcher != null) url = launcher.optString("url", "");
                    }
                    if (url.isEmpty()) url = item.optString("url", "");
                    if (title.isEmpty() || url.isEmpty()) continue;

                    boolean dup = false;
                    for (org.json.JSONObject a : lastAnnotations) {
                        org.json.JSONObject uc = a.optJSONObject("url_citation");
                        if (uc != null && url.equals(uc.optString("url", ""))) { dup = true; break; }
                    }
                    if (dup) continue;

                    org.json.JSONObject uc = new org.json.JSONObject();
                    uc.put("start_index", 0);
                    uc.put("end_index", 0);
                    uc.put("title", title);
                    uc.put("url", url);
                    org.json.JSONObject ann = new org.json.JSONObject();
                    ann.put("type", "url_citation");
                    ann.put("url_citation", uc);
                    lastAnnotations.add(ann);
                }
            }
        } catch (Exception e) {
            Logger.e("collectReferenceInfo: " + e.getMessage());
        }
    }

    /** 获取本轮回复的引用列表 (快照) */
    public static java.util.List<org.json.JSONObject> getAnnotations() {
        synchronized (lock) {
            return new java.util.ArrayList<>(lastAnnotations);
        }
    }

    // === 响应回调 ===

    public static void onResponse(String text, boolean isStreaming) {
        synchronized (lock) {
            lastFrames++;
            if (lastReply == null) lastReply = "";
            lastReply += text;
            if (currentSink != null && text != null) {
                currentSink.onDelta(text);
            }
            Logger.d("AiClientHook: onResponse frames=" + lastFrames + " replyLen=" + (lastReply != null ? lastReply.length() : 0));
        }
    }

    public static void onError(String error) {
        synchronized (lock) {
            lastError = error;
            Logger.d("AiClientHook: onError: " + error + " latch=" + (responseLatch != null));
            if (responseLatch != null) responseLatch.countDown();
        }
    }

    /**
     * v5.2.2: 仅在 answerStarted=true 时才释放闩锁。
     * 防止上一轮延迟到达的 Dialog.Finish (可能缺少 dialog_id, 绕过会话门控)
     * 在新一轮 StartStream 到达之前提前释放闩锁, 导致 lastReply 为空。
     */
    public static void onEnd() {
        synchronized (lock) {
            if (responseLatch == null) {
                Logger.d("AiClientHook: onEnd ignored, no latch");
                return;
            }
            if (!answerStarted) {
                Logger.d("AiClientHook: onEnd ignored, answerStarted=false (stale finish?)");
                return;
            }
            Logger.d("AiClientHook: onEnd → countDown, replyLen=" + (lastReply != null ? lastReply.length() : 0));
            responseLatch.countDown();
        }
    }

    // === 发送 ===

    private static ClassLoader hostClassLoader = null;

    private static ClassLoader getHostClassLoader() {
        if (hostClassLoader != null) return hostClassLoader;
        try {
            Class<?> atClass = Class.forName("android.app.ActivityThread");
            Method currentApp = atClass.getDeclaredMethod("currentApplication");
            currentApp.setAccessible(true);
            android.app.Application app = (android.app.Application) currentApp.invoke(null);
            if (app != null) {
                hostClassLoader = app.getClassLoader();
                return hostClassLoader;
            }
        } catch (Exception e) {
            Logger.e("AiClientHook: getHostClassLoader failed");
        }
        return AiClientHook.class.getClassLoader();
    }

    private static boolean awaitResponse() {
        try {
            return responseLatch.await(Config.READ_TIMEOUT, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /**
     * 发送文本查询并等待响应
     * v5.2.1: 先构建事件取得 event id (即响应 dialog_id), 再与闩锁同锁原子绑定;
     * 响应事件按 dialog_id 门控, 避免上一轮迟到的 Dialog.Finish 提前释放本轮闩锁
     */
    public static CliClient.CliResult chat(String text, String chatId, String agentId, CliClient.TextSink sink, Object images) {
        Logger.d("AiClientHook: chat called, text=" + truncate(text, 100));

        // 1. 先构建请求事件 (耗时操作, 在重置响应槽之前完成)
        Object event = buildRequestEvent(text);
        String eventId = extractEventId(event);

        // 2. 重置响应状态; 闩锁与会话 id 同锁原子设置, 不留被旧事件击中的窗口
        synchronized (lock) {
            lastReply = null;
            lastError = null;
            lastFrames = 0;
            answerStarted = false;
            lastAnnotations.clear();
            responseLatch = new CountDownLatch(1);
            currentDialogId = eventId;
            currentSink = (sink != null) ? sink::onDelta : null;
        }

        // 3. 发送: cr0.g.sendEvent 优先, f2.sendEvent 兜底
        if (event != null && dispatchEvent(event)) {
            Logger.d("AiClientHook: sent, dialog=" + eventId + ", waiting response...");
            boolean completed = awaitResponse();
            Logger.d("AiClientHook: awaitResponse returned " + completed + ", dialog=" + eventId);
            synchronized (lock) {
                String reply = lastReply != null ? lastReply : "";
                String error = lastError;
                if (!completed && error == null) {
                    error = "TIMEOUT: no response within " + Config.READ_TIMEOUT + "ms";
                }
                Logger.d("AiClientHook: done, dialog=" + eventId + " len=" + reply.length()
                        + " frames=" + lastFrames + " completed=" + completed + " error=" + error);
                return new CliClient.CliResult(reply, error, chatId, lastFrames);
            }
        }

        // 4. Intent 回退 (响应无法与 event id 关联, 关闭会话门控)
        synchronized (lock) { currentDialogId = null; }
        Logger.d("AiClientHook: event send failed, trying Intent fallback");
        trySendViaIntent(text);
        boolean completed = awaitResponse();
        synchronized (lock) {
            String reply = lastReply != null ? lastReply : "";
            String error = lastError;
            if (!completed && error == null) error = "TIMEOUT: Intent fallback";
            return new CliClient.CliResult(reply, error, chatId, lastFrames);
        }
    }

    /** v5.2.1: 构建 Nlp.RequestLargeLanguageModelContent 事件 (自 sendViaPostEvent 拆出) */
    private static Object buildRequestEvent(String text) {
        try {
            ClassLoader cl = getHostClassLoader();

            Object request = createLlmRequest(text, cl);
            if (request == null) {
                Logger.d("AiClientHook: failed to create RequestLargeLanguageModelContent");
                return null;
            }

            Object event = buildEventViaApiUtils(request, cl);
            if (event == null) {
                event = buildEventManual("Nlp", "RequestLargeLanguageModelContent", request, cl);
            }
            if (event == null) {
                Logger.d("AiClientHook: failed to build Event");
                return null;
            }
            Logger.d("AiClientHook: LLM Event ready, toString=" + truncate(event.toString(), 500));
            return event;
        } catch (Exception e) {
            Logger.e("AiClientHook: buildRequestEvent failed: " + e.getMessage());
            return null;
        }
    }

    /** v5.2.1: 分发事件, cr0.g.sendEvent 优先, f2.sendEvent 兜底 (自 sendViaPostEvent 拆出) */
    private static boolean dispatchEvent(Object event) {
        ClassLoader cl = getHostClassLoader();
        if (sendViaCr0g(event, cl)) {
            Logger.d("AiClientHook: sent via cr0.g.sendEvent!");
            return true;
        }
        if (sendViaF2(event, cl)) {
            Logger.d("AiClientHook: sent via f2.sendEvent!");
            return true;
        }
        Logger.d("AiClientHook: cr0.g and f2 sendEvent failed");
        return false;
    }

    /** v5.2.1: 从事件 toString 提取 header.id — 即响应事件的 dialog_id */
    private static String extractEventId(Object event) {
        if (event == null) return null;
        try {
            String s = event.toString();
            int i = s.indexOf("\"id\":\"");
            if (i < 0) return null;
            int start = i + 6;
            int end = s.indexOf('"', start);
            if (end > start) return s.substring(start, end);
        } catch (Exception ignored) {}
        return null;
    }

    /**
     * 通过 cr0.g.sendEvent() 发送文本查询 (v4.2)
     *
     * 正确的发送路径 (来自 TextQueryAction 分析):
     *   Nlp.RequestLargeLanguageModelContent -> APIUtils.buildEvent -> cr0.g.getInstance().sendEvent(event)
     *
     * 不再使用 Channel.postEvent (它接收内部包装类型 com.xiaomi.ai.core.b, 不是 Event)
     */
    private static boolean sendViaPostEvent(String text) {
        try {
            ClassLoader cl = getHostClassLoader();

            // 1. 创建 Nlp.RequestLargeLanguageModelContent (正确的文本查询 EventPayload)
            Object request = createLlmRequest(text, cl);
            if (request == null) {
                Logger.d("AiClientHook: failed to create RequestLargeLanguageModelContent");
                return false;
            }

            // 2. 构建 Event (RequestLargeLanguageModelContent 的 namespace=Nlp, name=RequestLargeLanguageModelContent)
            Object event = buildEventViaApiUtils(request, cl);
            if (event == null) {
                event = buildEventManual("Nlp", "RequestLargeLanguageModelContent", request, cl);
            }
            if (event == null) {
                Logger.d("AiClientHook: failed to build Event");
                return false;
            }
            Logger.d("AiClientHook: LLM Event ready, toString=" + truncate(event.toString(), 500));

            // 3. 通过 cr0.g.getInstance().sendEvent(event) 发送
            if (sendViaCr0g(event, cl)) {
                Logger.d("AiClientHook: sent via cr0.g.sendEvent!");
                return true;
            }

            // 4. 回退: f2.getInstance().sendEvent(event)
            if (sendViaF2(event, cl)) {
                Logger.d("AiClientHook: sent via f2.sendEvent!");
                return true;
            }

            Logger.d("AiClientHook: cr0.g and f2 sendEvent failed");
            return false;

        } catch (Exception e) {
            Logger.e("AiClientHook: sendViaPostEvent failed: " + e.getMessage());
            return false;
        }
    }

    /**
     * 通过 cr0.g.getInstance().sendEvent(Event) 发送
     */
    private static boolean sendViaCr0g(Object event, ClassLoader cl) {
        try {
            Class<?> cr0gClass = Class.forName(CLS_CR0_G, false, cl);
            Method getInstance = cr0gClass.getMethod("getInstance");
            Object cr0g = getInstance.invoke(null);
            if (cr0g == null) {
                Logger.d("AiClientHook: cr0.g.getInstance() returned null");
                return false;
            }

            Class<?> eventClass = Class.forName(CLS_EVENT, false, cl);
            Method sendEvent = cr0gClass.getMethod("sendEvent", eventClass);
            Object result = sendEvent.invoke(cr0g, event);
            Logger.d("AiClientHook: cr0.g.sendEvent(Event) result=" + result);
            return result instanceof Boolean ? (Boolean) result : true;
        } catch (ClassNotFoundException e) {
            Logger.d("AiClientHook: cr0.g class not found");
        } catch (NoSuchMethodException e) {
            Logger.d("AiClientHook: cr0.g.sendEvent(Event) method not found: " + e.getMessage());
        } catch (Exception e) {
            Logger.d("AiClientHook: cr0.g.sendEvent failed: " + e.getMessage());
        }
        return false;
    }

    /**
     * 通过 f2.getInstance().sendEvent(Event) 发送 (回退方案)
     */
    private static boolean sendViaF2(Object event, ClassLoader cl) {
        try {
            Class<?> f2Class = Class.forName("com.xiaomi.voiceassistant.f2", false, cl);
            Method getInstance = f2Class.getMethod("getInstance");
            Object f2 = getInstance.invoke(null);
            if (f2 == null) {
                Logger.d("AiClientHook: f2.getInstance() returned null");
                return false;
            }

            Class<?> eventClass = Class.forName(CLS_EVENT, false, cl);
            Method sendEvent = f2Class.getMethod("sendEvent", eventClass);
            Object result = sendEvent.invoke(f2, event);
            Logger.d("AiClientHook: f2.sendEvent(Event) result=" + result);
            return result instanceof Boolean ? (Boolean) result : true;
        } catch (ClassNotFoundException e) {
            Logger.d("AiClientHook: f2 class not found");
        } catch (NoSuchMethodException e) {
            Logger.d("AiClientHook: f2.sendEvent(Event) method not found: " + e.getMessage());
        } catch (Exception e) {
            Logger.d("AiClientHook: f2.sendEvent failed: " + e.getMessage());
        }
        return false;
    }

    /**
     * 创建 Nlp.RequestLargeLanguageModelContent(query)
     * 这是正确的文本查询 EventPayload, 不是语音用的 Nlp.Request
     */
    private static Object createLlmRequest(String text, ClassLoader cl) {
        try {
            Class<?> reqClass = Class.forName(CLS_LLM_REQUEST, false, cl);

            // 尝试 String 构造函数
            try {
                Constructor<?> ctor = reqClass.getConstructor(String.class);
                Object req = ctor.newInstance(text);
                Logger.d("AiClientHook: RequestLargeLanguageModelContent created via String ctor");
                return req;
            } catch (Exception ignored) {}

            // 无参构造 + setQuery
            Object req = reqClass.newInstance();
            try {
                Method setQuery = reqClass.getMethod("setQuery", String.class);
                setQuery.invoke(req, text);
                Logger.d("AiClientHook: RequestLargeLanguageModelContent created via setQuery");
                return req;
            } catch (Exception ignored) {}

            // 直接设置字段
            Field qField = findField(reqClass, "query");
            if (qField != null) {
                qField.setAccessible(true);
                qField.set(req, text);
                Logger.d("AiClientHook: RequestLargeLanguageModelContent created via field");
                return req;
            }

            Logger.d("AiClientHook: cannot set query on RequestLargeLanguageModelContent");
            return null;

        } catch (ClassNotFoundException e) {
            Logger.d("AiClientHook: RequestLargeLanguageModelContent class not found: " + CLS_LLM_REQUEST);
            return null;
        } catch (Exception e) {
            Logger.d("AiClientHook: createLlmRequest failed: " + e.getMessage());
            return null;
        }
    }

    /**
     * 创建 Nlp.PostBackRequest(token)
     * PostBackRequest implements EventPayload, 用于直接发送文字绕过 ASR
     */
    private static Object createPostBackRequest(String text, ClassLoader cl) {
        try {
            Class<?> pbrClass = Class.forName(CLS_POSTBACK_REQUEST, false, cl);
            try {
                Constructor<?> ctor = pbrClass.getConstructor(String.class);
                Object req = ctor.newInstance(text);
                Logger.d("AiClientHook: PostBackRequest created via String ctor, token=" + truncate(text, 50));
                return req;
            } catch (Exception ignored) {}
            // 无参构造 + setToken
            Object req = pbrClass.newInstance();
            Method setToken = pbrClass.getMethod("setToken", String.class);
            setToken.invoke(req, text);
            Logger.d("AiClientHook: PostBackRequest created via setToken");
            return req;
        } catch (ClassNotFoundException e) {
            Logger.d("AiClientHook: PostBackRequest class not found: " + CLS_POSTBACK_REQUEST);
        } catch (Exception e) {
            Logger.d("AiClientHook: createPostBackRequest failed: " + e.getMessage());
        }
        return null;
    }

    /**
     * 尝试创建 EventWrapper (com.xiaomi.ai.core.d 或 EventWrapper)
     * 运行时类名可能混淆, 尝试多个候选名称
     */
    private static Object createEventWrapper(Object event, String json, ClassLoader cl) {
        String[] candidates = {"com.xiaomi.ai.core.d", "com.xiaomi.ai.core.EventWrapper"};
        for (String name : candidates) {
            try {
                Class<?> eventClass = Class.forName(CLS_EVENT, false, cl);
                Class<?> ewClass = Class.forName(name, false, cl);
                Constructor<?> ewCtor = ewClass.getConstructor(eventClass, String.class);
                Object wrapper = ewCtor.newInstance(event, json);
                Logger.d("AiClientHook: EventWrapper created via " + name);
                return wrapper;
            } catch (ClassNotFoundException e) {
                Logger.d("AiClientHook: EventWrapper class not found: " + name);
            } catch (NoSuchMethodException e) {
                Logger.d("AiClientHook: EventWrapper ctor(Event,String) not found on " + name);
            } catch (Exception e) {
                Logger.d("AiClientHook: EventWrapper creation failed for " + name + ": " + e.getMessage());
            }
        }
        return null;
    }

    /**
     * 调用 Channel.postEvent(EventWrapper) — 按参数类型 com.xiaomi.ai.core.d 匹配
     * 方法名混淆, 但参数类型是唯一的
     */
    private static boolean callPostEventWithWrapper(Object channel, Object wrapper, Class<?> wrapperClass) {
        try {
            Class<?> cls = channel.getClass();
            while (cls != null && cls != Object.class) {
                for (Method m : cls.getDeclaredMethods()) {
                    if (m.getParameterCount() != 1) continue;
                    Class<?> paramType = m.getParameterTypes()[0];
                    if (paramType == wrapperClass || wrapperClass.isAssignableFrom(paramType)) {
                        m.setAccessible(true);
                        try {
                            Object result = m.invoke(channel, wrapper);
                            Logger.d("AiClientHook: postEvent(EventWrapper) via " + cls.getSimpleName()
                                + "." + m.getName() + " result=" + result);
                            if (result instanceof Boolean) return (Boolean) result;
                            return true;
                        } catch (Exception e) {
                            Logger.d("AiClientHook: " + m.getName() + " invoke failed: " + e.getMessage());
                        }
                    }
                }
                cls = cls.getSuperclass();
            }
            Logger.d("AiClientHook: no method accepting EventWrapper on channel");

            // 回退: 尝试直接调用 postEvent(Event)
            return callPostEvent(channel, wrapper.getClass().getMethod("getEvent").invoke(wrapper));
        } catch (Exception e) {
            Logger.e("AiClientHook: callPostEventWithWrapper failed: " + e.getMessage());
            return false;
        }
    }

    /**
     * 创建 Nlp.Request(text)
     * Nlp.Request implements EventPayload
     * 构造函数: Request(String query)
     */
    private static Object createNlpRequest(String text, ClassLoader cl) {
        try {
            Class<?> reqClass = Class.forName(CLS_NLP_REQUEST, false, cl);

            // 尝试 String 构造函数: Request(String query)
            try {
                Constructor<?> ctor = reqClass.getConstructor(String.class);
                Object req = ctor.newInstance(text);
                Logger.d("AiClientHook: Nlp.Request created via String ctor");
                return req;
            } catch (Exception ignored) {}

            // 回退: 无参构造 + setQuery
            Object req = reqClass.newInstance();
            try {
                Method setQuery = reqClass.getMethod("setQuery", String.class);
                setQuery.invoke(req, text);
                Logger.d("AiClientHook: Nlp.Request created via setQuery");
                return req;
            } catch (Exception ignored) {}

            // 最后回退: 直接设置字段
            Field qField = findField(reqClass, "query");
            if (qField != null) {
                qField.setAccessible(true);
                qField.set(req, text);
                Logger.d("AiClientHook: Nlp.Request created via field");
                return req;
            }

            Logger.d("AiClientHook: cannot set query on Nlp.Request");
            return null;

        } catch (ClassNotFoundException e) {
            Logger.d("AiClientHook: Nlp.Request class not found: " + CLS_NLP_REQUEST);
            return null;
        } catch (Exception e) {
            Logger.d("AiClientHook: createNlpRequest failed: " + e.getMessage());
            return null;
        }
    }

    /**
     * 通过 APIUtils.buildEvent(EventPayload) 构造 Event
     * 这是语音助手的标准方式, 正确处理 @NamespaceName 注解
     */
    private static Object buildEventViaApiUtils(Object payload, ClassLoader cl) {
        try {
            Class<?> apiUtilsClass = Class.forName(CLS_API_UTILS, false, cl);
            Class<?> eventPayloadClass = Class.forName(CLS_EVENT_PAYLOAD, false, cl);

            // APIUtils.buildEvent(EventPayload) - 单参数版本
            Method buildEvent = null;
            for (Method m : apiUtilsClass.getDeclaredMethods()) {
                if (m.getName().equals("buildEvent") && m.getParameterCount() == 1) {
                    Class<?> paramType = m.getParameterTypes()[0];
                    if (paramType.isAssignableFrom(payload.getClass()) || paramType == eventPayloadClass) {
                        buildEvent = m;
                        break;
                    }
                }
            }

            if (buildEvent == null) {
                // 尝试直接 getMethod
                try {
                    buildEvent = apiUtilsClass.getDeclaredMethod("buildEvent", eventPayloadClass);
                } catch (NoSuchMethodException ignored) {}
            }

            if (buildEvent != null) {
                buildEvent.setAccessible(true);
                Object event = buildEvent.invoke(null, payload);
                Logger.d("AiClientHook: Event built via APIUtils.buildEvent");
                return event;
            }

            Logger.d("AiClientHook: APIUtils.buildEvent(EventPayload) not found");
            return null;

        } catch (ClassNotFoundException e) {
            Logger.d("AiClientHook: APIUtils class not found: " + CLS_API_UTILS);
            return null;
        } catch (Exception e) {
            Logger.d("AiClientHook: buildEventViaApiUtils failed: " + e.getMessage());
            return null;
        }
    }

    /**
     * 手动构造 Event (回退方案)
     * Event = new Event(new EventHeader(namespace, name).setId(randomId), payload)
     */
    private static Object buildEventManual(String namespace, String name, Object payload, ClassLoader cl) {
        try {
            Class<?> eventClass = Class.forName(CLS_EVENT, false, cl);
            Class<?> ehClass = Class.forName(CLS_EVENT_HEADER, false, cl);

            // 创建 EventHeader(namespace, name)
            Object header = null;
            try {
                Constructor<?> ehCtor = ehClass.getConstructor(String.class, String.class);
                header = ehCtor.newInstance(namespace, name);
            } catch (Exception e) {
                Logger.d("AiClientHook: EventHeader(String,String) ctor failed: " + e.getMessage());
                header = ehClass.newInstance();
                callSetter(header, ehClass, "setName", name);
                callSetter(header, ehClass, "setNamespace", namespace);
            }

            // 设置 ID
            String eventId = java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 32);
            try {
                Method setId = ehClass.getMethod("setId", String.class);
                setId.invoke(header, eventId);
            } catch (Exception ignored) {}
            Logger.d("AiClientHook: manual EventHeader created, ns=" + namespace + " name=" + name + " id=" + eventId);

            // 创建 Event(header, payload)
            for (Constructor<?> ctor : eventClass.getDeclaredConstructors()) {
                if (ctor.getParameterCount() == 2) {
                    ctor.setAccessible(true);
                    try {
                        Object event = ctor.newInstance(header, payload);
                        Logger.d("AiClientHook: Event created via 2-arg ctor");
                        return event;
                    } catch (Exception e) {
                        Logger.d("AiClientHook: Event(header, payload) ctor failed: " + e.getMessage());
                    }
                }
            }

            // 回退: 无参构造 + setHeader + setPayload
            Object event = eventClass.newInstance();
            invokeSetter(event, "setHeader", header);
            invokeSetter(event, "setPayload", payload);
            Logger.d("AiClientHook: Event created via setters");
            return event;

        } catch (Exception e) {
            Logger.d("AiClientHook: buildEventManual failed: " + e.getMessage());
            return null;
        }
    }

    /**
     * 手动构造 Event (兼容旧调用, 默认 Nlp.Request)
     */
    private static Object buildEventManual(Object payload, ClassLoader cl) {
        return buildEventManual("Nlp", "Request", payload, cl);
    }

    /**
     * 调用 Channel.postEvent(Event) — 方法名混淆, 按参数类型搜索
     * v3.8: 添加详细诊断日志, 尝试所有单参数方法 (不限制类型, 让 JVM 判断)
     */
    private static boolean callPostEvent(Object channel, Object event) {
        try {
            Class<?> eventClass = event.getClass();
            Class<?> cls = channel.getClass();

            Logger.d("AiClientHook: callPostEvent searching on " + cls.getName()
                + " for arg type " + eventClass.getName());

            // v4.1: 混淆后的方法参数类型可能是 Object, 不能跳过
            // 第一遍: 精确按类型匹配 (包括 Object 参数, 因为混淆后参数类型可能为 Object)
            while (cls != null && cls != Object.class) {
                for (Method m : cls.getDeclaredMethods()) {
                    if (m.getDeclaringClass() == Object.class) continue;
                    if (m.getParameterCount() != 1) continue;

                    Class<?> paramType = m.getParameterTypes()[0];
                    String mn = m.getName();
                    if (mn.equals("hashCode") || mn.equals("equals") || mn.equals("toString")
                        || mn.equals("identityHashCode") || mn.startsWith("access$")) continue;

                    Logger.d("AiClientHook: candidate " + cls.getSimpleName() + "." + mn
                        + "(" + paramType.getSimpleName() + ")");

                    // v4.1: Object 参数也能匹配 (混淆后参数类型被擦除)
                    if (paramType.isAssignableFrom(eventClass) || paramType == Object.class) {
                        m.setAccessible(true);
                        try {
                            Object result = m.invoke(channel, event);
                            Logger.d("AiClientHook: postEvent via " + cls.getSimpleName() + "." + mn
                                + "(" + paramType.getSimpleName() + ") result=" + result);
                            if (result instanceof Boolean) return (Boolean) result;
                            return true;
                        } catch (Exception e) {
                            Logger.d("AiClientHook: " + mn + " invoke failed: " + e.getMessage());
                        }
                    }
                }
                cls = cls.getSuperclass();
            }

            // 第二遍: 不管类型, 暴力尝试所有单参数方法 (包括 Object 参数)
            Logger.d("AiClientHook: type match failed, brute-force trying all single-arg methods");
            cls = channel.getClass();
            while (cls != null && cls != Object.class) {
                for (Method m : cls.getDeclaredMethods()) {
                    if (m.getDeclaringClass() == Object.class) continue;
                    if (m.getParameterCount() != 1) continue;
                    String mn = m.getName();
                    if (mn.equals("hashCode") || mn.equals("equals") || mn.equals("toString")
                        || mn.equals("identityHashCode") || mn.startsWith("access$")) continue;

                    Class<?> paramType = m.getParameterTypes()[0];
                    m.setAccessible(true);
                    try {
                        Object result = m.invoke(channel, event);
                        Logger.d("AiClientHook: brute-force postEvent via " + cls.getSimpleName()
                            + "." + mn + "(" + paramType.getSimpleName() + ") result=" + result);
                        if (result instanceof Boolean) return (Boolean) result;
                        return true;
                    } catch (IllegalArgumentException e) {
                        // 类型不匹配, 继续下一个
                    } catch (Exception e) {
                        Logger.d("AiClientHook: " + mn + " brute-force failed: " + e.getMessage());
                    }
                }
                cls = cls.getSuperclass();
            }

            Logger.d("AiClientHook: no single-arg method accepting Event on any class");
        } catch (Exception e) {
            Logger.e("AiClientHook: callPostEvent failed: " + e.getMessage());
        }
        return false;
    }

    /**
     * v4.1: 通过 EventWrapper 包装后发送
     * Channel 的实际方法签名接受 EventWrapper (com.xiaomi.ai.core.d) 而非 Event
     */
    private static boolean trySendViaEventWrapper(Object channel, Object event, ClassLoader cl) {
        try {
            String json = event.toString();
            Object wrapper = createEventWrapper(event, json, cl);
            if (wrapper == null) {
                Logger.d("AiClientHook: EventWrapper creation failed");
                return false;
            }
            Logger.d("AiClientHook: EventWrapper created, trying postEvent(EventWrapper)");
            return callPostEventWithWrapper(channel, wrapper, wrapper.getClass());
        } catch (Exception e) {
            Logger.d("AiClientHook: trySendViaEventWrapper failed: " + e.getMessage());
            return false;
        }
    }

    /** 通过 Intent 发送文本查询 (回退方案) */
    private static void trySendViaIntent(String text) {
        try {
            android.content.Intent intent = new android.content.Intent("android.intent.action.ASSIST");
            intent.putExtra("android.intent.extra.ASSIST_INPUT_TEXT", text);
            intent.putExtra("query", text);
            intent.setPackage("com.miui.voiceassist");

            Class<?> atClass = Class.forName("android.app.ActivityThread");
            Method currentApp = atClass.getDeclaredMethod("currentApplication");
            currentApp.setAccessible(true);
            Context ctx = (Context) currentApp.invoke(null);
            if (ctx != null) {
                intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
                ctx.startActivity(intent);
                Logger.d("AiClientHook: sent via Intent");
            }
        } catch (Exception e) {
            Logger.d("AiClientHook: Intent fallback failed: " + e.getMessage());
            synchronized (lock) {
                lastError = "Intent fallback failed: " + e.getMessage();
                if (responseLatch != null) responseLatch.countDown();
            }
        }
    }

    // === 工具方法 ===

    private static Method findMethod(Class<?> cls, String name, int paramCount) {
        Class<?> c = cls;
        while (c != null && c != Object.class) {
            for (Method m : c.getDeclaredMethods()) {
                if (m.getName().equals(name) && m.getParameterCount() == paramCount) {
                    return m;
                }
            }
            c = c.getSuperclass();
        }
        return null;
    }

    /** 按返回类型和参数数量查找方法 (方法名可能混淆) */
    private static Method findMethodBySignature(Class<?> cls, Class<?> returnType, int paramCount) {
        Class<?> c = cls;
        while (c != null && c != Object.class) {
            for (Method m : c.getDeclaredMethods()) {
                if (m.getReturnType() == returnType && m.getParameterCount() == paramCount) {
                    return m;
                }
            }
            c = c.getSuperclass();
        }
        return null;
    }

    private static void invokeSetter(Object obj, String methodName, Object value) {
        Class<?> cls = obj.getClass();
        while (cls != null && cls != Object.class) {
            for (Method m : cls.getDeclaredMethods()) {
                if (m.getName().equals(methodName) && m.getParameterCount() == 1) {
                    Class<?> paramType = m.getParameterTypes()[0];
                    if (paramType.isAssignableFrom(value.getClass()) || paramType == Object.class) {
                        m.setAccessible(true);
                        try {
                            m.invoke(obj, value);
                            Logger.d("AiClientHook: " + methodName + " called successfully");
                            return;
                        } catch (Exception e) {
                            Logger.d("AiClientHook: " + methodName + " invoke failed: " + e.getMessage());
                        }
                    }
                }
            }
            cls = cls.getSuperclass();
        }
        Logger.d("AiClientHook: " + methodName + " not found");
    }

    private static void callSetter(Object obj, Class<?> cls, String methodName, String value) {
        try {
            Method m = cls.getMethod(methodName, String.class);
            m.setAccessible(true);
            m.invoke(obj, value);
        } catch (Exception ignored) {}
    }

    private static Object findChannelInstance() {
        try {
            Class<?> atClass = Class.forName("android.app.ActivityThread", false, getHostClassLoader());
            Method currentAt = atClass.getDeclaredMethod("currentActivityThread");
            currentAt.setAccessible(true);
            Object at = currentAt.invoke(null);
            if (at == null) return null;

            Field mServicesField = atClass.getDeclaredField("mServices");
            mServicesField.setAccessible(true);
            @SuppressWarnings("unchecked")
            java.util.Map<android.os.IBinder, android.app.Service> services =
                (java.util.Map<android.os.IBinder, android.app.Service>) mServicesField.get(at);
            if (services != null) {
                for (android.app.Service svc : services.values()) {
                    Object channel = findObjectByClassName(svc, CLS_CHANNEL_WRAPPER);
                    if (channel != null) return channel;
                }
            }

            Method currentApp = atClass.getDeclaredMethod("currentApplication");
            currentApp.setAccessible(true);
            android.app.Application app = (android.app.Application) currentApp.invoke(null);
            if (app != null) {
                return findObjectByClassName(app, CLS_CHANNEL_WRAPPER);
            }
        } catch (Exception e) {
            Logger.e("AiClientHook: findChannelInstance failed: " + e.getMessage());
        }
        return null;
    }

    private static Object findObjectByClassName(Object root, String targetClassName) {
        return findObjectByClassName(root, targetClassName, 0, new java.util.IdentityHashMap<>());
    }

    private static Object findObjectByClassName(Object obj, String targetClassName, int depth, java.util.IdentityHashMap<Object, Boolean> visited) {
        if (obj == null || depth > 8 || visited.containsKey(obj)) return null;
        visited.put(obj, Boolean.TRUE);

        Class<?> cls = obj.getClass();
        while (cls != null) {
            if (cls.getName().equals(targetClassName)) {
                return obj;
            }
            cls = cls.getSuperclass();
        }

        cls = obj.getClass();
        while (cls != null && cls != Object.class) {
            for (Field f : cls.getDeclaredFields()) {
                Class<?> type = f.getType();
                if (type.isPrimitive() || type == String.class || type == Class.class) continue;
                f.setAccessible(true);
                try {
                    Object val = f.get(obj);
                    if (val != null && !visited.containsKey(val)) {
                        Object found = findObjectByClassName(val, targetClassName, depth + 1, visited);
                        if (found != null) return found;
                    }
                } catch (Exception ignored) {}
            }
            cls = cls.getSuperclass();
        }
        return null;
    }

    private static Field findField(Class<?> cls, String fieldName) {
        Class<?> c = cls;
        while (c != null && c != Object.class) {
            try {
                return c.getDeclaredField(fieldName);
            } catch (NoSuchFieldException ignored) {}
            c = c.getSuperclass();
        }
        return null;
    }

    private static String truncate(String s, int max) {
        if (s == null) return "null";
        return s.length() > max ? s.substring(0, max) + "..." : s;
    }

    public static CliClient.CliResult chat(String text, String chatId, String agentId, CliClient.TextSink sink) {
        return chat(text, chatId, agentId, sink, null);
    }

    public static CliClient.CliResult chat(String text, String chatId, String agentId) {
        return chat(text, chatId, agentId, null, null);
    }
}
