package io.github.guocheng1378.xiaoaibridge;

import android.content.Context;
import android.os.Process;

import java.io.BufferedReader;
import java.io.FileReader;
import java.util.concurrent.atomic.AtomicBoolean;

/** 统一启动器: LibXposed 和老 Xposed 双入口共享, 防重复启动 */
public class BridgeStarter {
    private static final AtomicBoolean started = new AtomicBoolean(false);

    private static String getProcessName() {
        try {
            BufferedReader reader = new BufferedReader(new FileReader("/proc/self/cmdline"));
            String name = reader.readLine();
            reader.close();
            return name != null ? name.trim() : "";
        } catch (Exception e) {
            return "";
        }
    }

    public static void start(Context context) {
        if (!started.compareAndSet(false, true)) {
            Logger.d("BridgeStarter: already started, skip");
            return;
        }
        try {
            String procName = getProcessName();
            Logger.d("BridgeStarter: process=" + procName + " pid=" + Process.myPid());

            // 只在主进程启动 HTTP 服务器, :core 子进程没有 Channel
            if (procName.contains(":")) {
                Logger.d("BridgeStarter: sub-process, skip HTTP server");
                return;
            }

            // Application.attach() 阶段 getApplicationContext() 可能为 null,
            // 此时直接使用 attach 传入的 Context (即 Application 自身)
            Context appCtx = context.getApplicationContext();
            if (appCtx == null) appCtx = context;
            Config.loadFrom(appCtx);
            HttpServer server = new HttpServer(appCtx);
            server.start();
            Logger.d("XiaoAi Bridge started (v5.1.0) build=52");
        } catch (Throwable t) {
            Logger.e("Bridge start failed", t);
            started.set(false);
        }
    }
}
