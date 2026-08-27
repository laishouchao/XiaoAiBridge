package io.github.guocheng1378.xiaoaibridge;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * 模块配置: 支持通过设置界面(SharedPreferences)覆盖
 */
public class Config {
    public static final String PREFS = "xiaoaibridge_config";

    // HTTP 服务
    public static int HTTP_PORT = 8787;
    public static String API_TOKEN = "";        // 留空=不鉴权
    public static final String CLI_SOCKET = "osbot-cli";
    public static boolean STREAMING = true;
    public static long READ_TIMEOUT = 120000;
    public static String API_CHAT_ID = "api-gateway";
    public static int THREAD_POOL_SIZE = 4;

    // 限流 / 请求日志 / 重试 / Verbose
    public static int RATE_LIMIT = 0;           // 每分钟最大请求数, 0=关闭
    public static boolean REQ_LOGGING = true;   // 记录请求日志 (最近 100 条)
    public static boolean RETRY = true;         // AI 调用失败自动重试 1 次
    public static boolean VERBOSE = false;      // Verbose 调试日志

    // 运行时状态
    public static final String MODEL_NAME = "XiaoAi";
    public static String activeSocket = "voiceassist-internal";
    public static String defaultAgentId = "voiceassist.main";

    /** 从设置读取配置 (模块 UI 保存后, 宿主进程启动时调用) */
    public static void loadFrom(Context context) {
        try {
            SharedPreferences sp = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            HTTP_PORT = sp.getInt("http_port", HTTP_PORT);
            API_TOKEN = sp.getString("api_token", API_TOKEN);
            RATE_LIMIT = sp.getInt("rate_limit", RATE_LIMIT);
            REQ_LOGGING = sp.getBoolean("req_logging", REQ_LOGGING);
            RETRY = sp.getBoolean("retry", RETRY);
            VERBOSE = sp.getBoolean("verbose", VERBOSE);
            Logger.d("Config loaded: port=" + HTTP_PORT
                + " limit=" + RATE_LIMIT
                + " log=" + REQ_LOGGING
                + " retry=" + RETRY
                + " key=" + (API_TOKEN.isEmpty() ? "empty" : "***"));
        } catch (Exception e) {
            Logger.e("Config.loadFrom: " + e.getMessage());
        }
    }
}
