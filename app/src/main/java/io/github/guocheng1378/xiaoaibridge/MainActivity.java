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
 * XiaoAi Bridge v5.1 设置界面
 *
 * Material Design 风格卡片式布局，纯原生组件无外部依赖。
 */
public class MainActivity extends Activity {

    // ---- 色板 ----
    private static final int C_BG          = Color.parseColor("#F5F6FA");
    private static final int C_CARD        = Color.parseColor("#FFFFFF");
    private static final int C_PRIMARY     = Color.parseColor("#4F6EF7");
    private static final int C_PRIMARY_DK  = Color.parseColor("#3B56C9");
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
    private static final int C_CARD_RADIUS  = dp_static(16);
    private static final int C_BTN_RADIUS   = dp_static(24);
    private static final int C_INPUT_RADIUS  = dp_static(12);

    private EditText etPort, etToken, etRateLimit;
    private CheckBox cbReqLog, cbRetry, cbVerbose;
    private TextView tvStatus;
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
        tvVersion.setText("v5.1.0");
        tvVersion.setTextSize(13);
        tvVersion.setTextColor(Color.parseColor("#C8CDFF"));
        tvVersion.setTypeface(null, Typeface.BOLD);
        tvVersion.setPadding(0, dp(2), 0, dp(10));
        header.addView(tvVersion);

        TextView tvSub = new TextView(this);
        tvSub.setText("将超级小爱暴露为本机 OpenAI 兼容 API");
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
        tvHint.setText("保存后重启超级小爱使配置生效。\n\n"
                + "接入方式\n"
                + "Base URL: http://127.0.0.1:" + Config.HTTP_PORT + "/v1\n"
                + "Model: voiceassist.main\n\n"
                + "端点\n"
                + "/v1/chat/completions  /v1/chat\n"
                + "/v1/models  /health\n"
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

    // ==================== 状态检测 ====================

    private void checkStatus() {
        int port;
        try { port = Integer.parseInt(etPort.getText().toString().trim()); }
        catch (Exception e) { port = Config.HTTP_PORT; }
        final int targetPort = port;
        tvStatus.setText("\u25CF 检测中: 127.0.0.1:" + targetPort + " ...");
        tvStatus.setTextColor(C_SUBTITLE);
        new Thread(() -> {
            boolean ok = isPortOpen(targetPort);
            handler.post(() -> {
                if (ok) {
                    tvStatus.setText("\u25CF 服务运行中  |  http://127.0.0.1:" + targetPort);
                    tvStatus.setTextColor(C_SUCCESS);
                    tvStatus.setBackground(makeRoundRect(C_SUCCESS_BG, C_CARD_RADIUS));
                } else {
                    tvStatus.setText("\u25CB 服务未运行  |  127.0.0.1:" + targetPort
                            + "\n需 LSPosed 启用模块 + 作用域勾选 com.miui.voiceassist + 重启超级小爱");
                    tvStatus.setTextColor(C_ERROR);
                    tvStatus.setBackground(makeRoundRect(C_ERROR_BG, C_CARD_RADIUS));
                }
            });
        }).start();
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
        Config.REQ_LOGGING = cbReqLog.isChecked();
        Config.RETRY = cbRetry.isChecked();
        Config.VERBOSE = cbVerbose.isChecked();
        try { Config.RATE_LIMIT = Integer.parseInt(etRateLimit.getText().toString().trim()); }
        catch (Exception e) { Config.RATE_LIMIT = 0; }

        SharedPreferences sp = getSharedPreferences(Config.PREFS, Context.MODE_PRIVATE);
        SharedPreferences.Editor ed = sp.edit();
        ed.putInt("http_port", Config.HTTP_PORT);
        ed.putString("api_token", Config.API_TOKEN);
        ed.putBoolean("req_logging", Config.REQ_LOGGING);
        ed.putBoolean("retry", Config.RETRY);
        ed.putBoolean("verbose", Config.VERBOSE);
        ed.putInt("rate_limit", Config.RATE_LIMIT);
        ed.apply();

        showToast("配置已保存! 重启超级小爱后生效");
    }

    private void showToast(String msg) {
        android.widget.Toast.makeText(this, msg, android.widget.Toast.LENGTH_LONG).show();
    }
}
