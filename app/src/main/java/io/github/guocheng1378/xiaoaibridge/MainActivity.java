package io.github.guocheng1378.xiaoaibridge;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.GradientDrawable.Orientation;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.net.InetSocketAddress;
import java.net.Socket;

/**
 * XiaoAi Bridge v5.0 设置界面
 *
 * Material Design 风格卡片式布局，纯原生组件无外部依赖。
 * 配置项与功能保持不变，仅重构视觉效果。
 */
public class MainActivity extends Activity {

    // ---- 色板 ----
    private static final int C_BG          = Color.parseColor("#F5F6FA");
    private static final int C_CARD        = Color.parseColor("#FFFFFF");
    private static final int C_PRIMARY     = Color.parseColor("#4F6EF7");
    private static final int C_PRIMARY_DK  = Color.parseColor("#3B56C9");
    private static final int C_ACCENT      = Color.parseColor("#6C7FF8");
    private static final int C_TITLE       = Color.parseColor("#1A1B2E");
    private static final int C_SUBTITLE    = Color.parseColor("#8E92A6");
    private static final int C_LABEL       = Color.parseColor("#4A4D5E");
    private static final int C_HINT        = Color.parseColor("#A8ACBE");
    private static final int C_INPUT_BG    = Color.parseColor("#F7F8FC");
    private static final int C_INPUT_BR    = Color.parseColor("#E4E7F0");
    private static final int C_SUCCESS      = Color.parseColor("#16A34A");
    private static final int C_SUCCESS_BG   = Color.parseColor("#ECFDF5");
    private static final int C_ERROR        = Color.parseColor("#DC2626");
    private static final int C_ERROR_BG     = Color.parseColor("#FEF2F2");
    private static final int C_WARN         = Color.parseColor("#D97706");
    private static final int C_WARN_BG      = Color.parseColor("#FFFBEB");
    private static final int C_CARD_RADIUS  = dp_static(16);
    private static final int C_BTN_RADIUS   = dp_static(24);
    private static final int C_INPUT_RADIUS  = dp_static(12);

    private EditText etPort, etToken, etBaseUrl, etApiKey, etModel, etRoutes, etRateLimit;
    private CheckBox cbProxy, cbReqLog, cbRetry, cbVerbose;
    private TextView tvStatus, tvRoot;
    private final Handler handler = new Handler(Looper.getMainLooper());
;
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        // ---- 外层 ScrollView ----
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setBackgroundColor(C_BG);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(0), dp(0), dp(0), dp(32));

        // ==================== 渐变头部 ====================
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.VERTICAL);
        header.setPadding(dp(24), dp(40), dp(24), dp(28));
        GradientDrawable headerBg = new GradientDrawable(
                Orientation.TOP_BOTTOM,
                new int[]{C_PRIMARY, C_PRIMARY_DK});
        headerBg.setCornerRadius(0);
        header.setBackground(headerBg);

        TextView tvTitle = new TextView(this);
        tvTitle.setText("XiaoAi Bridge");
        tvTitle.setTextSize(28);
        tvTitle.setTypeface(null, Typeface.BOLD);
        tvTitle.setTextColor(Color.WHITE);
        tvTitle.setLetterSpacing(0.02f);
        header.addView(tvTitle);

        TextView tvVersion = new TextView(this);
        tvVersion.setText("v5.0.1");
        tvVersion.setTextSize(13);
        tvVersion.setTextColor(Color.parseColor("#C8CDFF"));
        tvVersion.setTypeface(null, Typeface.BOLD);
        tvVersion.setPadding(0, dp(2), 0, dp(10));
        header.addView(tvVersion);

        TextView tvSub = new TextView(this);
        tvSub.setText("将小米小爱语音助手暴露为本机 OpenAI 兼容 API");
        tvSub.setTextSize(13);
        tvSub.setTextColor(Color.parseColor("#D0D4FF"));
        tvSub.setLineSpacing(dp(4), 1f);
        header.addView(tvSub);

        root.addView(header);

        // 间距
        addSpacer(root, dp(20));

        // ==================== 状态卡片 ====================
        LinearLayout statusCard = cardWithPadding(dp(16));
        statusCard.setOrientation(LinearLayout.VERTICAL);

        TextView tvStatusHeader = sectionTitle("服务状态");
        statusCard.addView(tvStatusHeader);

        addSpacer(statusCard, dp(10));

        tvStatus = new TextView(this);
        tvStatus.setText("\u25CF 检测中...");
        tvStatus.setTextSize(14);
        tvStatus.setTypeface(null, Typeface.BOLD);
        tvStatus.setPadding(dp(16), dp(14), dp(16), dp(14));
        tvStatus.setBackground(makeRoundRect(C_INPUT_BG, C_CARD_RADIUS));
        statusCard.addView(tvStatus);

        addSpacer(statusCard, dp(10));

        Button btnRefresh = styledButton("刷新状态", C_PRIMARY, false);
        btnRefresh.setOnClickListener(v -> checkStatus());
        statusCard.addView(btnRefresh);

        addSpacer(statusCard, dp(16));

        tvRoot = new TextView(this);
        tvRoot.setText("\uD83D\uDD11 Root: 检测中...");
        tvRoot.setTextSize(14);
        tvRoot.setTypeface(null, Typeface.BOLD);
        tvRoot.setPadding(dp(16), dp(14), dp(16), dp(14));
        tvRoot.setBackground(makeRoundRect(C_INPUT_BG, C_CARD_RADIUS));
        statusCard.addView(tvRoot);

        addSpacer(statusCard, dp(10));

        Button btnRoot = styledButton("申请 Root 授权 (授权对象: 小爱同学)", C_LABEL, false);
        btnRoot.setOnClickListener(v -> requestRootNow());
        statusCard.addView(btnRoot);

        root.addView(cardWrap(statusCard));

        // ==================== 基本设置卡片 ====================
        addSpacer(root, dp(20));

        LinearLayout basicCard = cardWithPadding(dp(16));
        basicCard.setOrientation(LinearLayout.VERTICAL);

        basicCard.addView(sectionTitle("基本设置"));
        addSpacer(basicCard, dp(12));

        etPort = styledInput("HTTP 端口 (默认 8787)", "" + Config.HTTP_PORT, InputType.TYPE_CLASS_NUMBER);
        basicCard.addView(fieldBlock("HTTP 端口", etPort));

        etToken = styledInput("留空 = 不鉴权", Config.API_TOKEN,
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        basicCard.addView(fieldBlock("API Token", etToken));

        etRateLimit = styledInput("0 = 关闭限流", "" + Config.RATE_LIMIT, InputType.TYPE_CLASS_NUMBER);
        basicCard.addView(fieldBlock("限流 (次/分钟)", etRateLimit));

        root.addView(cardWrap(basicCard));

        // ==================== LLM 代理卡片 ====================
        addSpacer(root, dp(20));

        LinearLayout llmCard = cardWithPadding(dp(16));
        llmCard.setOrientation(LinearLayout.VERTICAL);

        llmCard.addView(sectionTitle("LLM 代理 (Function Calling)"));
        addSpacer(llmCard, dp(12));

        cbProxy = styledCheckBox("启用 LLM 代理 (带 tools 请求转发到外部 LLM)");
        cbProxy.setChecked(Config.LLM_PROXY_ENABLED);
        llmCard.addView(cbProxy);

        addSpacer(llmCard, dp(8));

        etBaseUrl = styledInput("https://api.deepseek.com/v1", Config.LLM_BASE_URL, InputType.TYPE_CLASS_TEXT);
        llmCard.addView(fieldBlock("Base URL", etBaseUrl));

        etApiKey = styledInput("仅存本机", Config.LLM_API_KEY,
                InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        llmCard.addView(fieldBlock("API Key", etApiKey));

        etModel = styledInput("deepseek-v4-flash", Config.LLM_MODEL, InputType.TYPE_CLASS_TEXT);
        llmCard.addView(fieldBlock("模型名", etModel));

        etRoutes = styledInput(
                "路由表: 每行  前缀=BaseURL|APIKey|模型名\n例: deepseek=https://api.deepseek.com/v1|sk-xxx|deepseek-chat",
                joinRoutes(Config.LLM_ROUTES), InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        etRoutes.setGravity(Gravity.TOP | Gravity.START);
        etRoutes.setMinLines(4);
        llmCard.addView(fieldBlock("路由表", etRoutes));

        root.addView(cardWrap(llmCard));

        // ==================== 高级设置卡片 ====================
        addSpacer(root, dp(20));

        LinearLayout advCard = cardWithPadding(dp(16));
        advCard.setOrientation(LinearLayout.VERTICAL);

        advCard.addView(sectionTitle("高级设置"));
        addSpacer(advCard, dp(12));

        cbReqLog = styledCheckBox("记录请求日志 (最近 100 条, GET /v1/admin/logs)");
        cbReqLog.setChecked(Config.REQ_LOGGING);
        advCard.addView(cbReqLog);

        addSpacer(advCard, dp(6));

        cbRetry = styledCheckBox("AI 调用失败自动重试 1 次");
        cbRetry.setChecked(Config.RETRY);
        advCard.addView(cbRetry);

        addSpacer(advCard, dp(6));

        cbVerbose = styledCheckBox("Verbose 调试日志");
        cbVerbose.setChecked(Config.VERBOSE);
        advCard.addView(cbVerbose);

        root.addView(cardWrap(advCard));

        // ==================== 保存按钮 ====================
        addSpacer(root, dp(24));

        Button btnSave = styledButton("保存配置", C_PRIMARY, true);
        btnSave.setTextSize(16);
        btnSave.setTypeface(null, Typeface.BOLD);
        btnSave.setOnClickListener(v -> saveConfig());
        root.addView(cardWrap(btnSave, dp(16)));

        // ==================== 接入提示 ====================
        addSpacer(root, dp(16));

        TextView tvHint = new TextView(this);
        tvHint.setText("保存后重启小爱同学使配置生效。\n\n"
                + "接入方式\n"
                + "Base URL: http://127.0.0.1:" + Config.HTTP_PORT + "/v1\n"
                + "Model: voiceassist.main\n\n"
                + "端点\n"
                + "/v1/chat/completions  /v1/chat  /v1/exec\n"
                + "/v1/tools  /v1/models  /health\n"
                + "/v1/admin/status  /v1/admin/logs");
        tvHint.setTextSize(12);
        tvHint.setTextColor(C_HINT);
        tvHint.setLineSpacing(dp(3), 1f);
        tvHint.setPadding(dp(20), dp(16), dp(20), dp(8));
        root.addView(tvHint);

        scroll.addView(root, new ScrollView.LayoutParams(
                ScrollView.LayoutParams.MATCH_PARENT,
                ScrollView.LayoutParams.MATCH_PARENT));
        setContentView(scroll);
        checkStatus();
    }

    // ==================== UI 构建辅助 ====================

    private TextView sectionTitle(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(16);
        tv.setTypeface(null, Typeface.BOLD);
        tv.setTextColor(C_TITLE);
        return tv;
    }

    private LinearLayout cardWithPadding(int pad) {
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(makeRoundRect(C_CARD, C_CARD_RADIUS));
        card.setPadding(pad, pad, pad, pad);
        return card;
    }

    private LinearLayout cardWrap(View inner) {
        return cardWrap(inner, dp(16));
    }

    private LinearLayout cardWrap(View inner, int hMargin) {
        LinearLayout wrap = new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        wrap.setPadding(hMargin, 0, hMargin, 0);
        wrap.addView(inner, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        return wrap;
    }

    private LinearLayout fieldBlock(String label, View edit) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(0, dp(8), 0, dp(8));

        TextView tv = new TextView(this);
        tv.setText(label);
        tv.setTextSize(13);
        tv.setTypeface(null, Typeface.BOLD);
        tv.setTextColor(C_LABEL);
        tv.setPadding(dp(2), 0, 0, dp(6));
        box.addView(tv);

        box.addView(edit);
        return box;
    }

    private EditText styledInput(String hint, String value, int inputType) {
        EditText et = new EditText(this);
        et.setHint(hint);
        et.setHintTextColor(C_HINT);
        et.setText(value == null ? "" : value);
        et.setInputType(inputType);
        et.setTextSize(15);
        et.setTextColor(C_TITLE);
        et.setPadding(dp(14), dp(12), dp(14), dp(12));
        et.setBackground(makeRoundRect(C_INPUT_BG, C_INPUT_RADIUS, C_INPUT_BR));
        return et;
    }

    private CheckBox styledCheckBox(String text) {
        CheckBox cb = new CheckBox(this);
        cb.setText(text);
        cb.setTextSize(14);
        cb.setTextColor(C_LABEL);
        cb.setPadding(dp(8), dp(8), dp(8), dp(8));
        return cb;
    }

    private Button styledButton(String text, int bgColor, boolean fullWidth) {
        Button btn = new Button(this);
        btn.setText(text);
        btn.setTextSize(14);
        btn.setTextColor(Color.WHITE);
        btn.setAllCaps(false);
        btn.setTypeface(null, Typeface.BOLD);
        btn.setPadding(dp(20), dp(14), dp(20), dp(14));
        btn.setBackground(makeRoundRect(bgColor, C_BTN_RADIUS));
        btn.setStateListAnimator(null);

        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                fullWidth ? LinearLayout.LayoutParams.MATCH_PARENT : LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.gravity = fullWidth ? Gravity.CENTER : Gravity.START;
        btn.setLayoutParams(lp);
        return btn;
    }

    private GradientDrawable makeRoundRect(int bgColor, int radius) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.RECTANGLE);
        d.setColor(bgColor);
        d.setCornerRadius(radius);
        return d;
    }

    private GradientDrawable makeRoundRect(int bgColor, int radius, int strokeColor) {
        GradientDrawable d = makeRoundRect(bgColor, radius);
        d.setStroke(dp(1), strokeColor);
        return d;
    }

    private void addSpacer(LinearLayout parent, int height) {
        View sp = new View(this);
        sp.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, height));
        parent.addView(sp);
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private static int dp_static(int v) {
        return v;
    }

    private String joinRoutes(java.util.Map<String, String> routes) {
        StringBuilder sb = new StringBuilder();
        if (routes != null) {
            for (java.util.Map.Entry<String, String> e : routes.entrySet()) {
                sb.append("ROUTE_").append(e.getKey()).append("=").append(e.getValue()).append("\n");
            }
        }
        return sb.toString();
    }

    // ==================== 状态检测 ====================

    private void checkStatus() {
        int port;
        try { port = Integer.parseInt(etPort.getText().toString().trim()); }
        catch (Exception e) { port = Config.HTTP_PORT; }
        final int targetPort = port;
        tvStatus.setText("\u25CF 检测中: 127.0.0.1:" + targetPort + " ...");
        tvStatus.setTextColor(C_SUBTITLE);
        tvRoot.setText("\uD83D\uDD11 Root: 检测中...");
        tvRoot.setTextColor(C_SUBTITLE);
        new Thread(() -> {
            boolean ok = isPortOpen(targetPort);
            // root 状态以小爱进程 (HTTP 服务所在进程) 内的实测为准
            Boolean rootInVoiceassist = null;
            if (ok) {
                String resp = httpGet("http://127.0.0.1:" + targetPort + "/v1/admin/status");
                if (resp != null) {
                    try {
                        rootInVoiceassist = new org.json.JSONObject(resp).optBoolean("root");
                    } catch (Exception ignored) { }
                }
            }
            final Boolean rootOk = rootInVoiceassist;
            handler.post(() -> {
                if (ok) {
                    tvStatus.setText("\u25CF 服务运行中  |  http://127.0.0.1:" + targetPort);
                    tvStatus.setTextColor(C_SUCCESS);
                    tvStatus.setBackground(makeRoundRect(C_SUCCESS_BG, C_CARD_RADIUS));
                } else {
                    tvStatus.setText("\u25CB 服务未运行  |  127.0.0.1:" + targetPort
                            + "\n需 LSPosed 启用模块 + 作用域勾选 com.miui.voiceassist + 重启小爱同学");
                    tvStatus.setTextColor(C_ERROR);
                    tvStatus.setBackground(makeRoundRect(C_ERROR_BG, C_CARD_RADIUS));
                }
                if (rootOk == null) {
                    tvRoot.setText("\uD83D\uDD11 Root: 无法检测 (服务未运行)\nRoot 授权对象是「小爱同学」(com.miui.voiceassist), 仅 /v1/exec 需要");
                    tvRoot.setTextColor(C_SUBTITLE);
                    tvRoot.setBackground(makeRoundRect(C_INPUT_BG, C_CARD_RADIUS));
                } else if (rootOk) {
                    tvRoot.setText("\uD83D\uDD11 Root: 已授权  |  com.miui.voiceassist\n/v1/exec (远程执行命令) 可用");
                    tvRoot.setTextColor(C_SUCCESS);
                    tvRoot.setBackground(makeRoundRect(C_SUCCESS_BG, C_CARD_RADIUS));
                } else {
                    tvRoot.setText("\uD83D\uDD11 Root: 未授权  |  com.miui.voiceassist\n点下方按钮触发授权弹窗, 在 Magisk/KernelSU 中允许「小爱同学」\n(对话 API 不受影响, 仅 /v1/exec 需要 Root)");
                    tvRoot.setTextColor(C_WARN);
                    tvRoot.setBackground(makeRoundRect(C_WARN_BG, C_CARD_RADIUS));
                }
            });
        }).start();
    }

    /** 通过 HTTP 服务在小爱进程内触发 su, 由 Magisk/KernelSU 弹窗授权 com.miui.voiceassist */
    private void requestRootNow() {
        int port;
        try { port = Integer.parseInt(etPort.getText().toString().trim()); }
        catch (Exception e) { port = Config.HTTP_PORT; }
        final int targetPort = port;
        tvRoot.setText("\uD83D\uDD11 Root: 请求授权中... (请在手机上查看 Magisk/KernelSU 弹窗)");
        tvRoot.setTextColor(C_SUBTITLE);
        new Thread(() -> {
            String resp = httpGet("http://127.0.0.1:" + targetPort + "/v1/admin/root");
            boolean granted = false;
            if (resp != null) {
                try {
                    granted = new org.json.JSONObject(resp).optBoolean("root");
                } catch (Exception ignored) { }
            }
            final boolean ok = granted;
            handler.post(() -> {
                if (ok) {
                    showToast("Root 授权成功!");
                } else {
                    showToast("未授权: 请在 Magisk/KernelSU 中允许「小爱同学」");
                }
                checkStatus();
            });
        }).start();
    }

    private String httpGet(String url) {
        try {
            java.net.HttpURLConnection conn = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
            conn.setConnectTimeout(1500);
            conn.setReadTimeout(5000);
            try (java.io.InputStream is = conn.getInputStream()) {
                java.io.ByteArrayOutputStream bos = new java.io.ByteArrayOutputStream();
                byte[] buf = new byte[2048];
                int n;
                while ((n = is.read(buf)) > 0) bos.write(buf, 0, n);
                return bos.toString("UTF-8");
            } finally {
                conn.disconnect();
            }
        } catch (Exception e) {
            return null;
        }
    }

    private boolean isPortOpen(int port) {
        try {
            Socket s = new Socket();
            s.connect(new InetSocketAddress("127.0.0.1", port), 800);
            s.close();
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    // ==================== 保存 ====================

    private void saveConfig() {
        try {
            int port = Integer.parseInt(etPort.getText().toString().trim());
            if (port < 1 || port > 65535) port = 8787;
            Config.HTTP_PORT = port;
        } catch (Exception e) { /* 保持默认 */ }

        Config.API_TOKEN = etToken.getText().toString().trim();
        Config.LLM_PROXY_ENABLED = cbProxy.isChecked();
        Config.LLM_BASE_URL = etBaseUrl.getText().toString().trim();
        Config.LLM_API_KEY = etApiKey.getText().toString().trim();
        Config.LLM_MODEL = etModel.getText().toString().trim();
        Config.REQ_LOGGING = cbReqLog.isChecked();
        Config.RETRY = cbRetry.isChecked();
        Config.VERBOSE = cbVerbose.isChecked();
        try { Config.RATE_LIMIT = Integer.parseInt(etRateLimit.getText().toString().trim()); }
        catch (Exception e) { Config.RATE_LIMIT = 0; }

        Config.LLM_ROUTES.clear();
        String[] lines = etRoutes.getText().toString().split("\\n");
        for (String line : lines) {
            line = line.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            int eq = line.indexOf('=');
            if (eq <= 0) continue;
            String prefix = line.substring(0, eq).trim();
            String val = line.substring(eq + 1).trim();
            if (prefix.startsWith("ROUTE_")) prefix = prefix.substring(6);
            if (!prefix.isEmpty() && !val.isEmpty()) Config.LLM_ROUTES.put(prefix, val);
        }

        SharedPreferences sp = getSharedPreferences(Config.PREFS, Context.MODE_PRIVATE);
        SharedPreferences.Editor ed = sp.edit();
        ed.putInt("http_port", Config.HTTP_PORT);
        ed.putString("api_token", Config.API_TOKEN);
        ed.putBoolean("llm_proxy_enabled", Config.LLM_PROXY_ENABLED);
        ed.putString("llm_base_url", Config.LLM_BASE_URL);
        ed.putString("llm_api_key", Config.LLM_API_KEY);
        ed.putString("llm_model", Config.LLM_MODEL);
        ed.putBoolean("req_logging", Config.REQ_LOGGING);
        ed.putBoolean("retry", Config.RETRY);
        ed.putBoolean("verbose", Config.VERBOSE);
        ed.putInt("rate_limit", Config.RATE_LIMIT);
        ed.putString("llm_routes", etRoutes.getText().toString().trim());
        ed.apply();

        showToast("配置已保存! 重启小爱同学后生效");
    }

    private void showToast(String msg) {
        android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_LONG).show();
    }
}
