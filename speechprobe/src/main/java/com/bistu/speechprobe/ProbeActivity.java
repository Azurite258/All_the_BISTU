package com.bistu.speechprobe;

import android.Manifest;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.speech.RecognitionListener;
import android.speech.RecognitionService;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/**
 * 语音识别探针：确认这台手机上第三方 App 能用什么识别引擎、连续识别撑不撑得住。
 *
 * 要回答三个问题：
 * 1. 系统注册了哪些 RecognitionService，默认是哪一个（小米机型上是不是小米自己的）；
 * 2. 说中文能不能转出来，转得准不准；
 * 3. 连续识别（模拟一节课）会不会断、每段要等多久。
 */
public class ProbeActivity extends Activity {

    private static final long RUN_MS = 30_000L;   // 一轮压测 30 秒
    private static final long GAP_MS = 250L;      // 段与段之间的间隔

    private final Handler main = new Handler(Looper.getMainLooper());
    private final StringBuilder log = new StringBuilder();

    private TextView envView;
    private TextView logView;
    private Button runButton;
    private SpeechRecognizer rec;

    private boolean running = false;
    private long runStartAt = 0;
    private long segStartAt = 0;
    private long lastSegMs = 0;
    private int segOk = 0;
    private int segErr = 0;
    private int segTotal = 0;
    private int totalChars = 0;
    private int rebuilds = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(16), dp(20), dp(16), dp(20));

        TextView title = new TextView(this);
        title.setText("语音识别探针");
        title.setTextSize(20);
        root.addView(title);

        envView = new TextView(this);
        envView.setTextSize(12);
        envView.setPadding(0, dp(10), 0, dp(10));
        root.addView(envView);

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        root.addView(buttons);

        Button refresh = new Button(this);
        refresh.setText("重新检查");
        refresh.setAllCaps(false);
        refresh.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                envView.setText(envReport());
            }
        });
        buttons.addView(refresh, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        runButton = new Button(this);
        runButton.setText("连续识别 30 秒");
        runButton.setAllCaps(false);
        runButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (running) {
                    stopRun("手动停止");
                } else {
                    startRun();
                }
            }
        });
        buttons.addView(runButton, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.4f));

        Button copy = new Button(this);
        copy.setText("复制结果");
        copy.setAllCaps(false);
        copy.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                String all = envView.getText() + "\n\n" + log;
                ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                if (cm != null) {
                    cm.setPrimaryClip(ClipData.newPlainText("语音探针结果", all));
                    toast("已复制，粘给我就行");
                }
            }
        });
        buttons.addView(copy, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        logView = new TextView(this);
        logView.setTextSize(12);
        logView.setPadding(0, dp(14), 0, 0);
        root.addView(logView);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(root, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        setContentView(scroll);

        envView.setText(envReport());
    }

    // ── 环境报告 ──────────────────────────────────────────────

    private String envReport() {
        StringBuilder sb = new StringBuilder();
        sb.append("设备：").append(Build.MANUFACTURER).append(" ").append(Build.MODEL).append('\n');
        sb.append("品牌：").append(Build.BRAND).append("   系统：Android ")
                .append(Build.VERSION.RELEASE).append("（API ").append(Build.VERSION.SDK_INT).append("）\n");
        sb.append("系统能否识别：").append(SpeechRecognizer.isRecognitionAvailable(this)).append('\n');

        String def = Settings.Secure.getString(getContentResolver(), "voice_recognition_service");
        sb.append("默认引擎：").append(def == null ? "未设置" : def).append("\n\n");

        sb.append("已注册的 RecognitionService：\n");
        Intent probe = new Intent(RecognitionService.SERVICE_INTERFACE);
        List<ResolveInfo> list;
        try {
            list = getPackageManager().queryIntentServices(probe, PackageManager.MATCH_ALL);
        } catch (Exception e) {
            list = new ArrayList<>();
        }
        if (list.isEmpty()) {
            sb.append("（无 —— 这台机器上第三方拿不到系统识别）\n");
        } else {
            for (ResolveInfo ri : list) {
                ServiceInfo si = ri.serviceInfo;
                sb.append("· ").append(si.packageName).append('/').append(si.name);
                if (!si.enabled) sb.append("（已禁用）");
                sb.append('\n');
            }
        }

        sb.append("\n相关包是否安装：\n");
        String[] pkgs = {
                "com.miui.voiceassist", "com.xiaomi.aiasst.service", "com.miui.voiceassistpro",
                "com.miui.voicetrigger", "com.miui.voicewakeup",
                "com.google.android.googlequicksearchbox", "com.google.android.tts"
        };
        boolean any = false;
        for (String p : pkgs) {
            if (installed(p)) {
                sb.append("· ").append(p).append(" ✓\n");
                any = true;
            }
        }
        if (!any) sb.append("（都没装）\n");
        return sb.toString();
    }

    private boolean installed(String pkg) {
        try {
            getPackageManager().getPackageInfo(pkg, 0);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    // ── 连续识别压测 ──────────────────────────────────────────

    private void startRun() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 1);
            return;
        }
        log.setLength(0);
        segOk = segErr = segTotal = totalChars = rebuilds = 0;
        lastSegMs = 0;
        logView.setText("");
        running = true;
        runButton.setText("停止");
        runStartAt = SystemClock.elapsedRealtime();
        append("—— 开始，持续 " + (RUN_MS / 1000) + " 秒 ——");
        nextSegment();
    }

    private void nextSegment() {
        if (!running) return;
        long elapsed = SystemClock.elapsedRealtime() - runStartAt;
        if (elapsed >= RUN_MS) {
            stopRun("时间到");
            return;
        }
        destroyRecognizer();

        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            append("系统没有可用识别服务，停止");
            stopRun("无引擎");
            return;
        }

        rec = SpeechRecognizer.createSpeechRecognizer(this);
        rec.setRecognitionListener(listener);
        segStartAt = SystemClock.elapsedRealtime();
        segTotal++;

        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN");
        intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
        intent.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, getPackageName());

        try {
            rec.startListening(intent);
        } catch (Exception e) {
            append("[#" + segTotal + "] 启动失败：" + e.getClass().getSimpleName());
            segErr++;
            scheduleNext();
        }
    }

    private final RecognitionListener listener = new RecognitionListener() {
        @Override
        public void onReadyForSpeech(Bundle params) { }

        @Override
        public void onBeginningOfSpeech() { }

        @Override
        public void onRmsChanged(float rmsdB) { }

        @Override
        public void onBufferReceived(byte[] buffer) { }

        @Override
        public void onEndOfSpeech() { }

        @Override
        public void onError(int error) {
            long ms = SystemClock.elapsedRealtime() - segStartAt;
            lastSegMs = ms;
            segErr++;
            append("[#" + segTotal + "] " + fmt(ms) + " 错误 " + errName(error) + "(" + error + ")");
            // 连续报错时退一步，别把 30 秒耗在无效重试上
            scheduleNext(GAP_MS * 4);
        }

        @Override
        public void onResults(Bundle results) {
            long ms = SystemClock.elapsedRealtime() - segStartAt;
            lastSegMs = ms;
            ArrayList<String> texts = results == null ? null
                    : results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
            String text = (texts == null || texts.isEmpty()) ? "" : texts.get(0);
            if (TextUtils.isEmpty(text)) {
                segErr++;
                append("[#" + segTotal + "] " + fmt(ms) + " 空结果");
            } else {
                segOk++;
                totalChars += text.length();
                append("[#" + segTotal + "] " + fmt(ms) + " " + text.length() + "字 | " + text);
            }
            scheduleNext();
        }

        @Override
        public void onPartialResults(Bundle partialResults) { }

        @Override
        public void onEvent(int eventType, Bundle params) { }
    };

    private void scheduleNext() {
        scheduleNext(GAP_MS);
    }

    private void scheduleNext(long delayMs) {
        main.postDelayed(new Runnable() {
            @Override
            public void run() {
                nextSegment();
            }
        }, delayMs);
    }

    private void stopRun(String why) {
        running = false;
        runButton.setText("连续识别 30 秒");
        destroyRecognizer();
        long total = SystemClock.elapsedRealtime() - runStartAt;
        String avg = segTotal == 0 ? "-" : fmt(total / Math.max(1, segTotal));
        StringBuilder sb = new StringBuilder();
        sb.append("\n—— 汇总（").append(why).append("）——\n");
        sb.append("总时长 ").append(fmt(total))
                .append("  尝试 ").append(segTotal).append(" 段")
                .append("  成功 ").append(segOk)
                .append("  失败 ").append(segErr)
                .append("  共 ").append(totalChars).append(" 字");
        if (segOk > 0) {
            sb.append("  平均每段 ").append(avg).append("、")
                    .append(totalChars / Math.max(1, segOk)).append(" 字");
        }
        append(sb.toString());
    }

    private void destroyRecognizer() {
        if (rec != null) {
            try {
                rec.cancel();
                rec.destroy();
            } catch (Exception ignored) {
            }
            rec = null;
            rebuilds++;
        }
    }

    // ── 杂项 ─────────────────────────────────────────────────

    private void append(String line) {
        log.append(line).append('\n');
        logView.setText(log);
    }

    private static String fmt(long ms) {
        return String.format(java.util.Locale.US, "%.1fs", ms / 1000.0);
    }

    private static String errName(int code) {
        switch (code) {
            case SpeechRecognizer.ERROR_AUDIO: return "录音出错";
            case SpeechRecognizer.ERROR_CLIENT: return "客户端错误";
            case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS: return "没有权限";
            case SpeechRecognizer.ERROR_NETWORK: return "网络错误";
            case SpeechRecognizer.ERROR_NETWORK_TIMEOUT: return "网络超时";
            case SpeechRecognizer.ERROR_NO_MATCH: return "没听懂";
            case SpeechRecognizer.ERROR_RECOGNIZER_BUSY: return "识别器忙";
            case SpeechRecognizer.ERROR_SERVER: return "服务端错误";
            case SpeechRecognizer.ERROR_SPEECH_TIMEOUT: return "没检测到说话";
            default: return "未知";
        }
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    private int dp(int v) {
        return Math.round(getResources().getDisplayMetrics().density * v);
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == 1) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startRun();
            } else {
                toast("没有麦克风权限，测不了");
            }
        }
    }

    @Override
    protected void onDestroy() {
        running = false;
        main.removeCallbacksAndMessages(null);
        destroyRecognizer();
        super.onDestroy();
    }
}
