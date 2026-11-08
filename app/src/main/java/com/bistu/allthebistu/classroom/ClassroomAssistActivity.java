package com.bistu.allthebistu.classroom;

import android.Manifest;
import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.graphics.Color;
import android.graphics.Typeface;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.SystemClock;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 课堂助手测试台。
 *
 * <p><b>两个输入源</b>：
 * <ol>
 *   <li><b>实时麦克风</b>：系统 SpeechRecognizer 循环重启。</li>
 *   <li><b>文本回放</b>：把真实转写稿按节奏喂进来，不用麦克风也能验证整条链路。</li>
 * </ol>
 *
 * <p><b>针对 SpeechRecognizer 的四处优化</b>（它本身只是个「遥控器」，真正识别在
 * 系统里另一个 App 的 RecognitionService 里做，所以只能靠用法去绕它的短板）：
 * <ol>
 *   <li><b>拉长静音超时</b>：默认 1 秒没声就判一段结束，老师一停顿就断。调到 6 秒，
 *       段数直接少一大半，空洞跟着少。</li>
 *   <li><b>partial 增量检测</b>：不等 onResults（那是「一段说完了」才给），
 *       改用 onPartialResults 边出字边判定。文字到手延迟从「段结束」降到 0.3~0.5 秒，
 *       这是对「赶紧备稿」最有价值的一条。</li>
 *   <li><b>重启零延迟</b>：onResults 后立刻重新 startListening，不再干等 350ms。
 *       撞上 ERROR_RECOGNIZER_BUSY 才退避。</li>
 *   <li><b>实例复用 + 看门狗</b>：同一个 SpeechRecognizer 对象反复 startListening，
 *       省掉每次 100~300ms 的创建开销；连续报错 5 次才销毁重建（引擎卡死时）。</li>
 * </ol>
 *
 * <p><b>仍然做不到的</b>：段与段之间那 0.1~0.3 秒的空洞消不掉 —— 这是系统 API 的
 * 结构限制。要真正连续，只能换流式 ASR（云服务或本地模型）。诊断里会估算累计丢了多久。
 */
public class ClassroomAssistActivity extends Activity implements RecognitionListener {

    private static final String PREF = "ai";
    private static final int REQ_AUDIO = 1011;
    private static final int REQ_NOTI = 1012;

    /** 说完之后多久判这一段结束。默认约 1000ms，太短会把句子切碎。 */
    private static final long SILENCE_COMPLETE_MS = 6000;
    /** 「可能说完了」的判定点，比上面短，用来提前收尾。 */
    private static final long SILENCE_POSSIBLY_MS = 3000;
    /** 一段至少说这么久才算数，避免半个字就开一段。 */
    private static final long MIN_SPEECH_MS = 800;

    /** 增量检测时的重叠字数：防止「请一个同学」正好被切在两次 partial 的交界上。 */
    private static final int OVERLAP_CHARS = 20;

    /** 撞上引擎忙时的退避。 */
    private static final long BUSY_BACKOFF_MS = 300;
    /** 一般错误的重启退避。 */
    private static final long RETRY_BACKOFF_MS = 1200;
    /** 连续错这么多次就认为引擎卡死，销毁重建。 */
    private static final int WATCHDOG_ERRORS = 5;

    private final Handler ui = new Handler(Looper.getMainLooper());
    private final TriggerEngine engine = new TriggerEngine();
    private final QuickAnswerClient client = new QuickAnswerClient();
    /** 最近的转写定稿，给 prompt 当上下文。 */
    private final List<String> recent = new ArrayList<>();
    /**
     * 答案缓存。老师一节课常把同一个问题问两遍（「刚才那个怎么取」），
     * 命中就复用，省一次模型调用，也省一次亮屏等待。
     */
    private final java.util.Map<String, String> answerCache = new java.util.HashMap<>();

    /** 当前使用的识别引擎；null 表示用系统默认。 */
    private ComponentName engineComponent;
    /** 所有候选引擎，按优先级排好。失败时轮换，而不是退回一个可能为空不存在的「默认」。 */
    private final List<ComponentName> candidates = new ArrayList<>();
    private int candidateIndex = 0;

    /**
     * 最近一次错误码。没有它就没法判断是「引擎不认中文」「没权限」还是「服务压根起不来」，
     * 只能瞎猜。
     */
    private int lastErrorCode = 0;
    /** 连续快速失败的次数，用来触发指数退避。 */
    private int fastFailures = 0;
    /** 权限回调时用来区分「这次要录音」还是「要开始识别」。 */
    private boolean pendingRecordStart = false;

    /**
     * 两次 startListening 之间的最小间隔。
     *
     * <p>之前出过大事：某个引擎起手就失败，而「无匹配」那条分支用的是零延迟重启，
     * 于是 12 秒里重启了 1022 次，字一个没收到，电倒是烧了。这个下限就是防那个的。
     */
    private static final long MIN_RESTART_MS = 260;
    /** 暗屏模式：屏幕压到接近全黑，只在触发出卡时短暂提亮。省电，也防老师路过看见。 */
    private boolean dimMode = false;
    /** 暗屏模式下，卡片亮多久再变暗。 */
    private static final long FLASH_MS = 8000;

    private SpeechRecognizer recognizer;
    private PowerManager.WakeLock wakeLock;
    private boolean running = false;
    private boolean waitingResult = false;
    private long sessionStart = 0;
    private long lastStartAt = 0;

    // ---- 增量检测状态 ----
    private String rolling = "";
    private int checkedUpTo = 0;

    // ---- 诊断统计 ----
    private int restarts = 0;
    private int errors = 0;
    private int consecutiveErrors = 0;
    private int charsHeard = 0;
    private long listeningMs = 0;

    private TextView status;
    private TextView preview;
    /** 连续同一个转写错误合并成一行用。 */
    private TextView lastAsrErrRow;
    private String lastAsrErrKey;
    private int lastAsrErrCount;
    private TextView recordChip;
    private LinearLayout transcriptBox;
    private ScrollView transcriptScroll;
    private LinearLayout cardBox;
    private TextView cardQuestion;
    private TextView cardAnswer;
    private TextView cardFollow;
    private Button mainButton;

    private String lastAnswer = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        buildUi();
        // 候选列表每次都建：它同时是「失败轮换」的备选池和报告里的诊断依据，
        // 只在首次运行时建会让下次启动显示「0 个候选」。
        buildCandidates();
        if (prefs().getBoolean("engineSet", false)) {
            // 用户手动选过（包括显式选「系统默认」），尊重他的选择
            String saved = prefs().getString("engine", "");
            ComponentName cn = ComponentName.unflattenFromString(saved);
            engineComponent = cn; // null 表示显式跟随系统默认
            int idx = cn == null ? -1 : candidates.indexOf(cn);
            if (idx >= 0) candidateIndex = idx;
        }
        status.setText("未开始 · 当前引擎：" + engineName() + " · 亮屏前台运行");
        ui.post(tick);
        // 等一帧再弹，避免和 buildUi 抢窗口
        ui.postDelayed(this::showIntroIfFirstTime, 400);
    }

    /**
     * 首次打开的一次性说明。
     *
     * <p>为什么非弹不可：这个功能有两条隐性成本，不提前说清楚就是坑人——
     * 一是<b>要小米 MiMo 的 API Key</b>，按 ¥0.5/小时计费（一节课约 0.4 元）；
     * 二是<b>整节课占用麦克风</b>，耗电明显。而且它还在开发中，功能不完整。
     *
     * <p>只弹一次，之后这些信息在「设置」和「诊断数据」里都还能查到。
     */
    private void showIntroIfFirstTime() {
        if (isFinishing() || isDestroyed()) return;
        if (prefs().getBoolean("introShown", false)) return;
        prefs().edit().putBoolean("introShown", true).apply();

        String msg = String.join("\n",
                "这个功能还在开发中，只有下面这套能跑通：",
                "录音 → 云端转写 → 听到点名时震动提醒 → 生成一句能接的话。",
                "",
                "用之前要知道两件事：",
                "",
                "1）需要小米 MiMo 的 API Key",
                "   去 platform.xiaomimimo.com 注册（要实名）后创建，",
                "   填进「设置 → 语音识别（小米 MiMo 云端）」。",
                "   按 ¥0.5/小时计费，一节课 45 分钟约 0.4 元；",
                "   新用户实名送 5 元体验金，够转写 10 小时。",
                "",
                "2）上课要一直占着麦克风",
                "   息屏也会继续录，耗电明显，建议插着充电宝。",
                "   不点「整课录音 / 开始听」就不会碰麦克风。",
                "",
                "转写稿和录音都存在 App 私有目录，不上传别处（除了发给你填的识别服务）。");

        new android.app.AlertDialog.Builder(this)
                .setTitle("AI 水课助手（开发中）")
                .setMessage(msg)
                .setPositiveButton("去填 Key", (d, w) -> showSettings())
                .setNegativeButton("先看看", null)
                .show();
    }

    // ---------------------------------------------------------------- UI

    private int dp(float v) {
        return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics());
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.parseColor("#F7F7F5"));
        // targetSdk 35+ 强制 edge-to-edge，内容会画到状态栏底下。
        // 不加这层内边距，顶栏按钮就被状态栏压住点不到（真机上就是这个症状）。
        root.setOnApplyWindowInsetsListener((v, insets) -> {
            android.graphics.Insets bars = insets.getInsets(WindowInsets.Type.systemBars());
            v.setPadding(0, bars.top, 0, bars.bottom);
            return insets;
        });

        // ---- 第一行：标题 + 设置 ----
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(16), dp(10), dp(8), dp(4));

        TextView title = new TextView(this);
        title.setText("AI 水课助手");
        title.setTextSize(18);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setTextColor(Color.parseColor("#1A1A1A"));
        bar.addView(title, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView settings = iconButton("设置");
        settings.setOnClickListener(v -> showSettings());
        bar.addView(settings);
        root.addView(bar);

        // ---- 第二行：三个等宽大按钮，不再挤在右上角 ----
        LinearLayout chips = new LinearLayout(this);
        chips.setOrientation(LinearLayout.HORIZONTAL);
        chips.setPadding(dp(12), dp(4), dp(12), dp(6));

        TextView engineBtn = chipButton("识别引擎");
        engineBtn.setOnClickListener(v -> showEnginePicker());
        chips.addView(engineBtn, weight());

        TextView diag = chipButton("诊断数据");
        diag.setOnClickListener(v -> showDiagnostics());
        chips.addView(diag, weight());

        TextView dim = chipButton("暗屏省电");
        dim.setOnClickListener(v -> {
            dimMode = !dimMode;
            styleChip(dim, dimMode);
            if (dimMode) applyDim(); else applyNormalBrightness();
            toast(dimMode ? "暗屏模式：只在出卡时亮 8 秒" : "已恢复常亮");
        });
        styleChip(dim, false);
        chips.addView(dim, weight());

        // 录音自检：绕开所有引擎，直接用 AudioRecord 采 5 秒，证明麦克风这条链路是通的。
        // 引擎全挂了的时候，这是唯一能判断「是麦克风的问题还是引擎的问题」的手段。
        TextView mic = chipButton("录音自检");
        mic.setOnClickListener(v -> startMicCheck());
        chips.addView(mic, weight());

        // 整节课录音：前台服务常驻，和识别引擎完全解耦。
        // 引擎被小米封死之后，这块是所有替代方案（云 ASR / 本地模型）的地基。
        recordChip = chipButton("整课录音");
        recordChip.setOnClickListener(v -> toggleLectureRecord());
        chips.addView(recordChip, weight());
        root.addView(chips);

        // ---- 第三行：一键导出，全宽，是给人按的那种 ----
        Button export = new Button(this);
        export.setText("一键导出诊断报告（点这个发我）");
        export.setAllCaps(false);
        export.setTextSize(15);
        export.setTypeface(Typeface.DEFAULT_BOLD);
        export.setOnClickListener(v -> exportReport());
        LinearLayout.LayoutParams exportLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(54));
        exportLp.setMargins(dp(16), dp(4), dp(16), dp(8));
        root.addView(export, exportLp);

        status = new TextView(this);
        status.setText("未开始 · 亮屏前台运行，息屏会被系统掐掉");
        status.setTextSize(12);
        status.setTextColor(Color.parseColor("#8A8A80"));
        status.setPadding(dp(16), 0, dp(16), dp(6));
        // 暗屏时黑得几乎看不见按钮，点一下状态行临时提亮 8 秒
        status.setOnClickListener(v -> {
            if (dimMode) flashBright();
        });
        root.addView(status);

        // 实时预览：partial 结果边出边显示。没有这一行，用户会以为它死了。
        preview = new TextView(this);
        preview.setTextSize(13);
        preview.setTypeface(Typeface.DEFAULT, Typeface.ITALIC);
        preview.setTextColor(Color.parseColor("#9A9A90"));
        preview.setPadding(dp(16), 0, dp(16), dp(8));
        preview.setSingleLine(true);
        preview.setVisibility(View.GONE);
        root.addView(preview);

        transcriptScroll = new ScrollView(this);
        transcriptScroll.setBackgroundColor(Color.WHITE);
        transcriptBox = new LinearLayout(this);
        transcriptBox.setOrientation(LinearLayout.VERTICAL);
        transcriptBox.setPadding(dp(16), dp(12), dp(16), dp(12));
        transcriptScroll.addView(transcriptBox, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        root.addView(transcriptScroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        cardBox = new LinearLayout(this);
        cardBox.setOrientation(LinearLayout.VERTICAL);
        cardBox.setBackgroundColor(Color.parseColor("#FFF6DA"));
        cardBox.setPadding(dp(16), dp(12), dp(16), dp(12));
        cardBox.setVisibility(View.GONE);

        TextView cardHead = new TextView(this);
        cardHead.setText("被点到就说这个");
        cardHead.setTextSize(12);
        cardHead.setTextColor(Color.parseColor("#8A6D00"));
        cardHead.setTypeface(Typeface.DEFAULT_BOLD);
        cardBox.addView(cardHead);

        cardQuestion = cardLine("#6B6B60", 13);
        cardBox.addView(cardQuestion);
        cardAnswer = cardLine("#1A1A1A", 16);
        cardAnswer.setTypeface(Typeface.DEFAULT_BOLD);
        cardBox.addView(cardAnswer);
        cardFollow = cardLine("#6B6B60", 13);
        cardBox.addView(cardFollow);

        TextView copy = new TextView(this);
        copy.setText("复制答案");
        copy.setTextSize(13);
        copy.setTextColor(Color.parseColor("#B47900"));
        copy.setPadding(0, dp(6), 0, 0);
        copy.setOnClickListener(v -> {
            android.content.ClipboardManager cm =
                    (android.content.ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(android.content.ClipData.newPlainText("answer", lastAnswer));
            Toast.makeText(this, "已复制", Toast.LENGTH_SHORT).show();
        });
        cardBox.addView(copy);
        root.addView(cardBox);

        LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.HORIZONTAL);
        bottom.setPadding(dp(12), dp(10), dp(12), dp(16));

        mainButton = bottomButton("开始听");
        mainButton.setOnClickListener(v -> toggle());
        bottom.addView(mainButton, weight());

        Button replayButton = bottomButton("回放示例");
        replayButton.setOnClickListener(v -> replay());
        bottom.addView(replayButton, weight());

        Button poolButton = bottomButton("问题池");
        poolButton.setOnClickListener(v -> showPool());
        bottom.addView(poolButton, weight());

        root.addView(bottom);
        setContentView(root);
    }

    private LinearLayout.LayoutParams weight() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        p.setMargins(dp(4), 0, dp(4), 0);
        return p;
    }

    private TextView iconButton(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(13);
        t.setTextColor(Color.parseColor("#3A5A8A"));
        t.setPadding(dp(8), dp(6), dp(8), dp(6));
        return t;
    }

    private TextView cardLine(String color, int sp) {
        TextView t = new TextView(this);
        t.setTextSize(sp);
        t.setTextColor(Color.parseColor(color));
        t.setPadding(0, dp(4), 0, 0);
        t.setLineSpacing(0, 1.35f);
        return t;
    }

    private Button bottomButton(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(14);
        return b;
    }

    /** 等宽的大号「胶囊」按钮，替换原来挤在右上角的小字。 */
    private TextView chipButton(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(14);
        t.setGravity(Gravity.CENTER);
        t.setPadding(0, dp(12), 0, dp(12));
        t.setClickable(true);
        t.setFocusable(true);
        styleChip(t, false);
        return t;
    }

    /** 胶囊的两种状态：默认灰底、激活黄底。 */
    private void styleChip(TextView t, boolean active) {
        t.setTextColor(Color.parseColor(active ? "#8A6D00" : "#3A3A34"));
        android.graphics.drawable.GradientDrawable bg = new android.graphics.drawable.GradientDrawable();
        bg.setColor(Color.parseColor(active ? "#FFF0B8" : "#EDEBE6"));
        bg.setCornerRadius(dp(10));
        t.setBackground(bg);
    }

    // ---------------------------------------------------------------- 整节课录音

    /** 录音服务的回调：前台时挂上，界面能看到电平和时长。 */
    private final RecordService.Listener recordListener = new RecordService.Listener() {
        @Override
        public void onLevel(int peak, long seconds) {
            ui.post(() -> status.setText(String.format(Locale.CHINA,
                    "录音中 %02d:%02d · 电平 %d%s",
                    seconds / 60, seconds % 60, peak, peak < 20 ? "（很安静）" : "")));
        }

        @Override
        public void onFinished(String path, long seconds, long bytes, String error) {
            ui.post(() -> {
                styleChip(recordChip, false);
                String size = String.format(Locale.CHINA, "%.1f MB", bytes / 1048576.0);
                String cost = String.format(Locale.CHINA, "¥%.2f", RecordService.audioMsTotal / 3600000.0 * 0.5);
                String msg = String.join("\n",
                        "时长：" + (seconds / 60) + " 分 " + (seconds % 60) + " 秒",
                        "音频：" + size + "（16k / 16bit / 单声道）",
                        "转写：成功 " + RecordService.segDone + " 段 · 失败 " + RecordService.segFail
                                + " 段 · " + RecordService.charTotal + " 字 · 约 " + cost,
                        "文件：",
                        path == null ? "未生成" : path,
                        RecordService.textPath == null ? "" : "转写稿：\n" + RecordService.textPath,
                        RecordService.lastAsrError == null ? "" : "\n最后错误：" + RecordService.lastAsrError,
                        error == null ? "" : "\n错误：" + error);
                status.setText("录音已结束");
                showSimple("整课录音", msg.trim());
            });
        }

        @Override
        public void onTranscript(int segIndex, long startSec, String text, String err, long costMs) {
            ui.post(() -> {
                if (err != null) {
                    // 同一个错误会连续出现（比如没填 Key 时每 12 秒报一次），
                    // 合并成一行并累加次数，否则转写区会被几十条同样的字刷满。
                    if (err.equals(lastAsrErrKey) && lastAsrErrRow != null) {
                        lastAsrErrCount++;
                        lastAsrErrRow.setText(String.format(Locale.CHINA,
                                "%02d:%02d  〔连续失败 ×%d〕%s",
                                startSec / 60, startSec % 60, lastAsrErrCount, err));
                    } else {
                        lastAsrErrKey = err;
                        lastAsrErrCount = 1;
                        lastAsrErrRow = appendRow(startSec, "〔转写失败〕" + err, true);
                    }
                    status.setText("转写失败 · " + err);
                    return;
                }
                // 成功就把失败统计清掉，下一次失败重新计
                lastAsrErrKey = null;
                lastAsrErrRow = null;
                lastAsrErrCount = 0;
                appendTranscriptAt(startSec, text, false);
                status.setText(String.format(Locale.CHINA,
                        "转写中 · 第 %d 段 · %d 字 · 用时 %dms",
                        segIndex, RecordService.charTotal, costMs));
            });
        }

        @Override
        public void onHit(TriggerEngine.Hit hit, String text) {
            ui.post(() -> {
                String tag = hit.target == null ? "无近期问题" : "已锁定问题";
                appendTranscript("▲ 点名「" + hit.word + "」（" + tag + "）", true);
                generate(text, hit.target);
            });
        }
    };

    private void toggleLectureRecord() {
        if (RecordService.running) {
            startService(new Intent(this, RecordService.class).setAction(RecordService.ACTION_STOP));
        } else {
            startLectureRecord();
        }
    }

    private void startLectureRecord() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            // 记住这次要的是「录音」而不是「开始识别」，回调里别搞混
            pendingRecordStart = true;
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_AUDIO);
            return;
        }
        // Android 13+ 前台服务的通知要显式申请，否则通知栏不显示（服务仍在跑）
        if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTI);
        }
        RecordService.listener = recordListener;
        startService(new Intent(this, RecordService.class).setAction(RecordService.ACTION_START));
        styleChip(recordChip, true);
        toast("开始整节课录音，可以锁屏放着");
    }

    // ---------------------------------------------------------------- 录音自检

    /**
     * 录音自检：不经过任何识别引擎，直接用 {@link AudioRecord} 采 5 秒，存成 WAV。
     *
     * <p>存在的意义：现在所有引擎都报错，必须先分清是「麦克风拿不到音频」还是
     * 「引擎不工作」。这一步跟引擎完全无关，能给出确定答案。
     *
     * <p>顺便算出 RMS 和峰值 —— 如果峰值是 0，说明采到的全是静音（权限或路由问题）；
     * 如果有波形但很小，说明离得远或系统降噪压过头了。
     */
    private void startMicCheck() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_AUDIO);
            return;
        }
        status.setText("录音自检中… 对着手机说几句话（5 秒）");
        toast("开始录音 5 秒，请说话");

        new Thread(() -> {
            final int rate = 16000;
            final int seconds = 5;
            int minBuf = AudioRecord.getMinBufferSize(rate,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
            if (minBuf <= 0) minBuf = 3200;
            AudioRecord ar = null;
            try {
                ar = new AudioRecord(MediaRecorder.AudioSource.MIC, rate,
                        AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, minBuf * 4);
                if (ar.getState() != AudioRecord.STATE_INITIALIZED) {
                    final String msg = "麦克风初始化失败（可能被别的 App 占用，或 MIUI 权限没给）";
                    ui.post(() -> { status.setText("录音自检失败"); showSimple("录音自检", msg); });
                    ar.release();
                    return;
                }
                java.io.ByteArrayOutputStream pcm = new java.io.ByteArrayOutputStream(rate * seconds * 2);
                short[] chunk = new short[1600];
                long sumSq = 0;
                int samples = 0;
                int peak = 0;
                int nonZero = 0;
                int minVal = 0;
                int maxVal = 0;
                long end = SystemClock.elapsedRealtime() + seconds * 1000L;
                long nextUi = 0;

                ar.startRecording();
                while (SystemClock.elapsedRealtime() < end) {
                    int r = ar.read(chunk, 0, chunk.length);
                    if (r <= 0) continue;
                    for (int i = 0; i < r; i++) {
                        int v = chunk[i];
                        int a = Math.abs(v);
                        if (a > peak) peak = a;
                        if (v != 0) nonZero++;
                        if (v < minVal) minVal = v;
                        if (v > maxVal) maxVal = v;
                        sumSq += (long) v * v;
                        pcm.write(v & 0xFF);
                        pcm.write((v >> 8) & 0xFF);
                    }
                    samples += r;

                    // 实时电平：图书馆里也能看着它随环境音跳动，确认麦克风是活的
                    long now = SystemClock.elapsedRealtime();
                    if (now >= nextUi) {
                        nextUi = now + 400;
                        final int p = peak;
                        ui.post(() -> status.setText("录音中… 峰值 " + p + " / 32767"));
                    }
                }
                ar.stop();
                ar.release();

                double rms = samples == 0 ? 0 : Math.sqrt((double) sumSq / samples);
                double rmsDbfs = rms <= 0 ? -120 : 20 * Math.log10(rms / 32768.0);
                double sec = samples / (double) rate;

                String path = "";
                try {
                    java.io.File dir = getExternalFilesDir(null);
                    if (dir != null) {
                        java.io.File f = new java.io.File(dir, "miccheck.wav");
                        java.io.FileOutputStream fos = new java.io.FileOutputStream(f);
                        writeWavHeader(fos, pcm.size(), rate);
                        pcm.writeTo(fos);
                        fos.close();
                        path = f.getAbsolutePath();
                    }
                } catch (Exception ignored) {
                }

                // 判定改成「有没有波动」，而不是「声音够不够大」。
                // 图书馆里不用说话也能验证：只要采到的电平在变，就说明麦克风在收环境声。
                final int swing = maxVal - minVal;
                final String verdict;
                if (nonZero == 0) {
                    verdict = "▶ 完全没信号：样本全是 0，麦克风没工作（权限被拒 / 被占用 / 硬件问题）";
                } else if (swing >= 3) {
                    verdict = "▶ 麦克风是活的：采到环境声，波动幅度 " + swing
                            + (peak < 100 ? "。现在环境很安静，说话时会明显变大。" : "。");
                } else {
                    verdict = "▶ 电平平直、没有波动（波动 0~2）：麦克风可能没真正打开";
                }

                final String msg = String.join("\n",
                        "采集时长：" + String.format(Locale.CHINA, "%.1f", sec) + " 秒",
                        "采样：" + rate + " Hz · 16bit · 单声道",
                        "样本数：" + samples + "（非零 " + nonZero + "）",
                        "峰值：" + peak + "  ·  最小 " + minVal + "  ·  波动 " + swing,
                        String.format(Locale.CHINA, "平均音量：%.2f dBFS", rmsDbfs),
                        "",
                        verdict,
                        path.isEmpty() ? "" : "\nWAV 已存：\n" + path);

                final int peakOut = peak;
                final byte[] pcmOut = pcm.toByteArray();
                ui.post(() -> {
                    status.setText("录音自检完成 · 峰值 " + peakOut);
                    new android.app.AlertDialog.Builder(this)
                            .setTitle("录音自检结果")
                            .setMessage(msg)
                            .setPositiveButton("用 MiMo 转写这段", (d, w) -> tryMimoTranscribe(pcmOut))
                            .setNeutralButton("再测一次", (d, w) -> startMicCheck())
                            .setNegativeButton("知道了", null)
                            .show();
                });

            } catch (Exception e) {
                if (ar != null) {
                    try { ar.release(); } catch (Exception ignored) {}
                }
                final String msg = e.getClass().getSimpleName() + "：" + e.getMessage();
                ui.post(() -> { status.setText("录音自检失败"); showSimple("录音自检", msg); });
            }
        }).start();
    }

    /**
     * 拿刚采到的 PCM 直接调 MiMo 云端识别。
     *
     * <p>为什么复用在「录音自检」里而不是单开一个按钮：一次操作同时验证两件事——
     * 麦克风采到的是不是真实声音、云端识别能不能拿到文本。如果这里能出字，
     * 说明「录音 + 上传 + 识别 + 返回」整条链路全通，后面只差把它接到实时上。
     */
    private void tryMimoTranscribe(byte[] pcm) {
        String key = prefs().getString("mimoKey", "");
        if (key.trim().isEmpty()) {
            new android.app.AlertDialog.Builder(this)
                    .setTitle("还没填 MiMo API Key")
                    .setMessage("去「设置」里的「语音识别（小米 MiMo 云端）」填上。\n\n"
                            + "Key 在 platform.xiaomimimo.com 注册后创建，形如 mimo_...\n"
                            + "计费 ¥0.5/小时，一节课 45 分钟约 ¥0.38。")
                    .setPositiveButton("去填", (d, w) -> showSettings())
                    .setNegativeButton("取消", null)
                    .show();
            return;
        }

        status.setText("正在上传到 MiMo 识别…");
        new Thread(() -> {
            MimoAsrClient client = new MimoAsrClient();
            MimoAsrClient.Result r = client.transcribe(pcm,
                    prefs().getString("mimoBase", MimoAsrClient.DEFAULT_BASE),
                    key,
                    prefs().getString("mimoModel", MimoAsrClient.DEFAULT_MODEL));

            ui.post(() -> {
                if (!r.ok) {
                    status.setText("MiMo 识别失败");
                    showSimple("MiMo 识别失败",
                            "原因：" + r.error
                                    + "\n\n音频 " + r.audioMs + "ms，耗时 " + r.costMs + "ms"
                                    + "\n\n常见原因：Key 无效、余额为 0、网络到不了 api.xiaomimimo.com");
                    return;
                }
                status.setText("MiMo 识别成功 · " + r.text.length() + " 字 · " + r.costMs + "ms");
                appendTranscript(r.text, false);
                showSimple("MiMo 识别成功",
                        "文本：\n" + r.text
                                + "\n\n音频 " + r.audioMs + "ms"
                                + " · 耗时 " + r.costMs + "ms"
                                + String.format(Locale.CHINA, " · 费用约 ¥%.4f",
                                r.audioMs / 3600000.0 * 0.5));
            });
        }, "mimo-test").start();
    }

    /** 标准 44 字节 WAV 头，方便直接把文件发给别人听。 */    private static void writeWavHeader(java.io.OutputStream out, int pcmLen, int rate) throws java.io.IOException {
        int channels = 1, bits = 16;
        int byteRate = rate * channels * bits / 8;
        java.nio.ByteBuffer h = java.nio.ByteBuffer.allocate(44).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        h.put("RIFF".getBytes()); h.putInt(36 + pcmLen); h.put("WAVE".getBytes());
        h.put("fmt ".getBytes()); h.putInt(16); h.putShort((short) 1); h.putShort((short) channels);
        h.putInt(rate); h.putInt(byteRate); h.putShort((short) (channels * bits / 8)); h.putShort((short) bits);
        h.put("data".getBytes()); h.putInt(pcmLen);
        out.write(h.array());
    }

    private void showSimple(String title, String msg) {
        new android.app.AlertDialog.Builder(this)
                .setTitle(title).setMessage(msg)
                .setPositiveButton("知道了", null)
                .show();
    }

    // ---------------------------------------------------------------- 一键导出诊断

    /**
     * 把排查需要的东西一次性打出来，走系统分享面板发出去。
     *
     * <p>内容刻意做得啰嗦：设备型号、targetSdk、系统默认引擎、所有注册的
     * RecognitionService、本次会话统计、最近转写、问题池。这些都是「为什么在这台手机上
     * 不工作」的判据，少一项就得再来一轮。
     */
    private void exportReport() {
        String report = buildReport();

        // 同时落一份文件，方便用数据线拷（路径会写在报告尾部）
        String path = "";
        try {
            java.io.File dir = getExternalFilesDir(null);
            if (dir != null) {
                java.io.File f = new java.io.File(dir, "diagnose.txt");
                java.io.FileOutputStream fos = new java.io.FileOutputStream(f);
                fos.write(report.getBytes(java.nio.charset.StandardCharsets.UTF_8));
                fos.close();
                path = f.getAbsolutePath();
            }
        } catch (Exception ignored) {
        }

        String full = report + (path.isEmpty() ? "" : "\n\n（已存文件：" + path + "）");
        Intent send = new Intent(Intent.ACTION_SEND);
        send.setType("text/plain");
        send.putExtra(Intent.EXTRA_SUBJECT, "课堂助手诊断报告");
        send.putExtra(Intent.EXTRA_TEXT, full);
        startActivity(Intent.createChooser(send, "把这份报告发出去"));
    }

    private String buildReport() {
        StringBuilder sb = new StringBuilder();
        String now = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA)
                .format(new java.util.Date());

        sb.append("=== 课堂助手诊断报告 ===\n");
        sb.append("时间：").append(now).append("\n");
        sb.append("设备：").append(android.os.Build.MANUFACTURER).append(" ")
                .append(android.os.Build.MODEL)
                .append("  ·  Android ").append(android.os.Build.VERSION.RELEASE)
                .append("（API ").append(android.os.Build.VERSION.SDK_INT).append("）\n");
        sb.append("应用：").append(getPackageName())
                .append("  ·  targetSdk ").append(getApplicationInfo().targetSdkVersion).append("\n");

        sb.append("\n--- 语音识别 ---\n");
        sb.append("isRecognitionAvailable：").append(SpeechRecognizer.isRecognitionAvailable(this)).append("\n");
        String def = android.provider.Settings.Secure.getString(
                getContentResolver(), "voice_recognition_service");
        sb.append("系统默认引擎：").append(def == null ? "未设置" : def).append("\n");
        sb.append("本应用指定：").append(engineComponent == null ? "跟随系统默认" : engineComponent.flattenToString())
                .append("（").append(engineName()).append("）\n");
        sb.append("候选引擎：").append(candidates.size()).append(" 个（当前第 ")
                .append(candidates.isEmpty() ? 0 : candidateIndex + 1).append(" 个）\n");
        sb.append("最近错误：").append(lastErrorCode == 0 ? "无"
                : errorName(lastErrorCode) + "(" + lastErrorCode + ")").append("\n");
        sb.append("引擎权限：").append(enginePermission()).append("\n");
        sb.append("连续快速失败：").append(fastFailures).append(" 次\n");

        List<ResolveInfo> list = getPackageManager().queryIntentServices(
                new Intent("android.speech.RecognitionService"), 0);
        sb.append("注册的 RecognitionService：").append(list.size()).append(" 个\n");
        for (int i = 0; i < list.size(); i++) {
            ServiceInfo si = list.get(i).serviceInfo;
            if (si == null) continue;
            boolean mi = si.packageName.contains("miui") || si.packageName.contains("xiaomi");
            sb.append("  ").append(i + 1).append(". ").append(si.packageName)
                    .append(mi ? "   ← 小米" : "").append("\n");
            sb.append("     ").append(si.name).append("\n");
        }

        sb.append("\n--- 本次会话 ---\n");
        long total = sessionStart == 0 ? 0 : (SystemClock.elapsedRealtime() - sessionStart) / 1000;
        sb.append("时长：").append(total).append(" 秒\n");
        sb.append("识别重启：").append(restarts).append(" 次\n");
        sb.append("报错次数：").append(errors).append(" 次\n");
        sb.append("收到字数：").append(charsHeard).append(" 字\n");
        sb.append("平均每段：").append(restarts == 0 ? "—" : (total / restarts) + " 秒").append("\n");
        sb.append("强触发：").append(engine.strongCount())
                .append(" 次  ·  弱触发：").append(engine.weakCount()).append(" 次\n");
        sb.append("当前冷却：").append(engine.currentCooldown() / 1000).append(" 秒\n");
        sb.append("答案缓存：").append(answerCache.size()).append(" 条\n");
        sb.append("屏幕模式：").append(dimMode ? "暗屏" : "常亮").append("\n");
        sb.append("停顿判定：").append(SILENCE_COMPLETE_MS).append("ms\n");

        sb.append("\n--- 权限 ---\n");
        sb.append("RECORD_AUDIO：")
                .append(checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                        == PackageManager.PERMISSION_GRANTED ? "已授权" : "未授权").append("\n");
        sb.append("AI 配置：").append(prefs().getString("apiKey", "").isEmpty() ? "未填 Key" : "已填 Key")
                .append("  ·  模型 ").append(prefs().getString("model", "deepseek-chat")).append("\n");

        sb.append("\n--- 语音识别（MiMo 云端）---\n");
        String mimoKey = prefs().getString("mimoKey", "");
        sb.append("MiMo Key：").append(mimoKey.trim().isEmpty() ? "未填（无法转写）"
                : "已填（" + (mimoKey.length() > 8 ? mimoKey.substring(0, 6) + "…" + mimoKey.substring(mimoKey.length() - 4) : "***") + "）").append("\n");
        sb.append("接口地址：").append(prefs().getString("mimoBase", MimoAsrClient.DEFAULT_BASE)).append("\n");
        sb.append("模型：").append(prefs().getString("mimoModel", MimoAsrClient.DEFAULT_MODEL)).append("\n");
        sb.append("分段长度：12 秒（单请求上限 10MB，12 秒只占约 0.5MB）\n");
        sb.append("整课录音：成功 ").append(RecordService.segDone)
                .append(" 段 · 失败 ").append(RecordService.segFail)
                .append(" 段 · 积压丢弃 ").append(RecordService.droppedTotal)
                .append(" 段 · 累计 ").append(RecordService.charTotal).append(" 字\n");
        sb.append("累计音频：").append(RecordService.audioMsTotal / 1000).append(" 秒")
                .append(String.format(Locale.CHINA, "（约 ¥%.2f）", RecordService.audioMsTotal / 3600000.0 * 0.5))
                .append("\n");
        if (RecordService.lastAsrError != null) {
            sb.append("最近转写错误：").append(RecordService.lastAsrError).append("\n");
        }
        if (RecordService.textPath != null) {
            sb.append("转写稿：").append(RecordService.textPath).append("\n");
        }

        sb.append("\n--- 最近转写（").append(recent.size()).append(" 条）---\n");
        if (recent.isEmpty()) sb.append("（空）\n");
        for (String r : recent) sb.append("· ").append(r).append("\n");

        sb.append("\n--- 问题池 ---\n");
        List<TriggerEngine.Question> pool = engine.questionPool(SystemClock.elapsedRealtime());
        if (pool.isEmpty()) sb.append("（空）\n");
        for (TriggerEngine.Question q : pool) sb.append("· ").append(q.text).append("\n");

        return sb.toString();
    }

    // ---------------------------------------------------------------- 屏幕亮度 / 震动

    /**
     * 暗屏：窗口亮度压到 0.04。
     *
     * <p>这是全流程里最省电的一招 —— 亮屏功耗比识别本身还大（100% 亮度 45 分钟约 8~12%）。
     * 顺带解决「屏幕亮一整节课，老师走过来就露馅」。
     */
    private void applyDim() {
        WindowManager.LayoutParams lp = getWindow().getAttributes();
        lp.screenBrightness = 0.04f;
        getWindow().setAttributes(lp);
    }

    private void applyNormalBrightness() {
        WindowManager.LayoutParams lp = getWindow().getAttributes();
        lp.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE;
        getWindow().setAttributes(lp);
    }

    /** 出卡时短暂提亮，8 秒后自动回暗。 */
    private void flashBright() {
        if (!dimMode) return;
        WindowManager.LayoutParams lp = getWindow().getAttributes();
        lp.screenBrightness = 0.85f;
        getWindow().setAttributes(lp);
        ui.removeCallbacks(dimBack);
        ui.postDelayed(dimBack, FLASH_MS);
    }

    private final Runnable dimBack = new Runnable() {
        @Override
        public void run() {
            if (dimMode) applyDim();
        }
    };

    /** 出卡时震一下 —— 暗屏状态下你根本看不到屏幕，全靠这一下。 */
    private void buzz() {
        try {
            Vibrator v = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);
            if (v != null && v.hasVibrator()) {
                v.vibrate(VibrationEffect.createOneShot(220, VibrationEffect.DEFAULT_AMPLITUDE));
            }
        } catch (Exception ignored) {
        }
    }

    // ---------------------------------------------------------------- 会话控制

    private void toggle() {
        if (running) stopListening(); else startListening();
    }

    private void acquireWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) return;
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "classroom:listen");
        wakeLock.setReferenceCounted(false);
        wakeLock.acquire(90 * 60 * 1000L); // 上限 90 分钟，够两节课
    }

    private void releaseWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        wakeLock = null;
    }

    private void startListening() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, REQ_AUDIO);
            return;
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            toast("这台机器没有可用的识别服务");
            return;
        }
        engine.reset();
        recent.clear();
        transcriptBox.removeAllViews();
        cardBox.setVisibility(View.GONE);
        preview.setVisibility(View.GONE);
        restarts = 0;
        errors = 0;
        consecutiveErrors = 0;
        charsHeard = 0;
        listeningMs = 0;
        sessionStart = SystemClock.elapsedRealtime();
        running = true;
        mainButton.setText("停止");
        acquireWakeLock();
        if (dimMode) applyDim();
        createRecognizer();
        startSession();
    }

    private void stopListening() {
        running = false;
        mainButton.setText("开始听");
        releaseWakeLock();
        applyNormalBrightness();
        if (recognizer != null) {
            try {
                recognizer.cancel();
                recognizer.destroy();
            } catch (Exception ignored) {
            }
            recognizer = null;
        }
        preview.setVisibility(View.GONE);
        status.setText("已停止 · " + engine.summary());
    }

    private void createRecognizer() {
        if (recognizer != null) return;
        // 指定了引擎就按 ComponentName 建，能绕开「系统默认」这个黑盒
        recognizer = engineComponent == null
                ? SpeechRecognizer.createSpeechRecognizer(this)
                : SpeechRecognizer.createSpeechRecognizer(this, engineComponent);
        if (recognizer == null) {
            // 指定失败了就退回默认，别整场都起不来
            engineComponent = null;
            recognizer = SpeechRecognizer.createSpeechRecognizer(this);
        }
        recognizer.setRecognitionListener(this);
    }

    private void destroyRecognizer() {
        if (recognizer == null) return;
        try {
            recognizer.cancel();
            recognizer.destroy();
        } catch (Exception ignored) {
        }
        recognizer = null;
    }

    private void startSession() {
        if (!running) return;
        createRecognizer();
        Intent i = buildIntent();
        lastStartAt = SystemClock.elapsedRealtime();
        waitingResult = true;
        try {
            recognizer.startListening(i);
        } catch (Exception e) {
            waitingResult = false;
            ui.postDelayed(this::startSession, RETRY_BACKOFF_MS);
        }
    }

    /** 优化点 1：把静音超时拉长，段数少一半，空洞跟着少。 */
    private Intent buildIntent() {
        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN");
        i.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        i.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
        i.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, SILENCE_COMPLETE_MS);
        i.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, SILENCE_POSSIBLY_MS);
        i.putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, MIN_SPEECH_MS);
        return i;
    }

    /**
     * 重启识别会话。
     *
     * <p>做过两次修正：一是加了 {@link #MIN_RESTART_MS} 下限，防止「失败—立刻重试—再失败」
     * 变成每十几毫秒一次的死循环（真机上一度 12 秒重启 1022 次）；二是把重置滚动状态
     * 放在这里，保证每次新会话都从干净状态开始。
     */
    private void restart(long delay) {
        if (!running) return;
        waitingResult = false;
        rolling = "";
        checkedUpTo = 0;

        long since = SystemClock.elapsedRealtime() - lastStartAt;
        long wait = Math.max(delay, MIN_RESTART_MS - since);
        if (wait < 0) wait = 0;
        if (wait == 0) {
            ui.post(this::startSession);
        } else {
            ui.postDelayed(this::startSession, wait);
        }
    }

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (running) {
                long s = (SystemClock.elapsedRealtime() - sessionStart) / 1000;
                status.setText(String.format(Locale.CHINA,
                        "%02d:%02d · %s · %s · 重启 %d · 错 %d",
                        s / 60, s % 60, engineName(), engine.summary(), restarts, errors));
            }
            ui.postDelayed(this, 1000);
        }
    };

    // ---------------------------------------------------------------- 识别回调

    /**
     * 优化点 2：partial 结果边走边说就边判定。
     *
     * <p>不把 partial 追加到转写流（它会反复自我修正，追加会刷屏），只做两件事：
     * 更新灰色预览行、检测强触发。检测时带 {@link #OVERLAP_CHARS} 字重叠，
     * 保证「请一个同学」被切在两次 partial 交界上也能命中。
     */
    @Override
    public void onPartialResults(Bundle partial) {
        String p = firstOf(partial);
        if (p == null || p.isEmpty()) return;

        preview.setVisibility(View.VISIBLE);
        preview.setText("…" + tail(p, 40));

        rolling = p;
        if (rolling.length() <= checkedUpTo) return; // 只是自我修正，没有新增字
        int from = Math.max(0, checkedUpTo - OVERLAP_CHARS);
        String slice = rolling.substring(from);
        checkedUpTo = rolling.length();

        TriggerEngine.Hit hit = engine.detectStrong(slice, SystemClock.elapsedRealtime());
        if (hit != null) onTriggered(hit, null);
    }

    @Override
    public void onResults(Bundle results) {
        waitingResult = false;
        String text = firstOf(results);
        if (text != null && text.trim().length() > 1) {
            text = text.trim();
            charsHeard += text.length();
            appendTranscript(text);
            recent.add(text);
            while (recent.size() > 12) recent.remove(0);

            // 定稿才入问题池（弱触发），并兜底一次强触发：partial 可能没覆盖到
            TriggerEngine.Hit hit = engine.feed(text, SystemClock.elapsedRealtime());
            if (hit != null && hit.level == TriggerEngine.Level.STRONG) {
                onTriggered(hit, text);
            }
        }
        restarts++;
        fastFailures = 0;   // 收到结果说明引擎是活的，退避清零
        restart(0); // 优化点 3：立刻接上，不等
    }

    @Override
    public void onError(int error) {
        waitingResult = false;
        errors++;
        consecutiveErrors++;
        lastErrorCode = error;

        // 「启动后 400ms 内就报错」才算异常快速失败；正常听完一段（几秒）不算。
        // 中间地带不动，避免把正常的静音超时也当成故障。
        long since = SystemClock.elapsedRealtime() - lastStartAt;
        if (since < 400) fastFailures++;
        else if (since > 1500) fastFailures = 0;

        long backoff = RETRY_BACKOFF_MS;
        if (fastFailures >= 8) backoff = 8000;
        else if (fastFailures >= 4) backoff = 3000;

        // 这些错误代表「这个引擎没法用」：客户端错、服务端错、缺权限、不支持中文
        boolean dead = error == SpeechRecognizer.ERROR_CLIENT
                || error == SpeechRecognizer.ERROR_SERVER
                || error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS
                || error == SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED
                || error == SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE
                || error == SpeechRecognizer.ERROR_TOO_MANY_REQUESTS
                || consecutiveErrors >= WATCHDOG_ERRORS;
        if (dead) {
            consecutiveErrors = 0;
            if (candidates.size() > 1) {
                rotateCandidate(); // 内部会 destroy 并换下一个
            } else if (error == SpeechRecognizer.ERROR_CLIENT
                    || error == SpeechRecognizer.ERROR_SERVER
                    || error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
                // 这几类错误会让 SpeechRecognizer 对象彻底失效，必须重建，不能复用
                destroyRecognizer();
            }
            restarts++;
            restart(backoff);
            return;
        }

        // 6/7 = 没听到人说话或没匹配上，属正常静音。正常情况下立即重开；
        // 但如果它是在紧密失败（400ms 内反复报），就跟着退避，别烧电。
        if (error == SpeechRecognizer.ERROR_NO_MATCH
                || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT) {
            consecutiveErrors = 0;
            restarts++;
            restart(fastFailures >= 8 ? 8000 : 0);
            return;
        }

        // 8 = RECOGNIZER_BUSY，上一个会话还没释放完
        if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY) {
            restarts++;
            restart(BUSY_BACKOFF_MS);
            return;
        }

        restarts++;
        restart(backoff);
    }

    @Override
    public void onEndOfSpeech() {
        // 这里已经知道「这一句说完了」，但文字还在路上，等 onResults
        waitingResult = true;
    }

    @Override public void onReadyForSpeech(Bundle p) {
        consecutiveErrors = 0;
    }

    @Override public void onBeginningOfSpeech() {}
    @Override public void onRmsChanged(float rms) {}
    @Override public void onBufferReceived(byte[] buffer) {}
    @Override public void onEvent(int type, Bundle p) {}

    // ---------------------------------------------------------------- 核心

    /** 命中强触发：震动 + 提亮 + 上屏高亮 + 生成接话。 */
    private void onTriggered(TriggerEngine.Hit hit, String fullText) {
        buzz();
        flashBright();
        appendTranscript("▲ 命中「" + hit.word + "」（" + (hit.target == null ? "无近期问题" : "已锁定问题") + "）", true);

        // 同一个问题不重复调模型：老师一节课常把同一句问两遍
        if (hit.target != null) {
            String cached = answerCache.get(hit.target);
            if (cached != null) {
                renderAnswer(cached, 0);
                appendTranscript("（复用上次答案，没调模型）", true);
                return;
            }
        }
        generate(fullText != null ? fullText : hit.context, hit.target);
    }

    private void generate(String triggerContext, String target) {
        String ctx = String.join("\n", recent);
        if (!ctx.contains(triggerContext)) ctx = ctx + "\n" + triggerContext;
        String baseUrl = prefs().getString("baseUrl", "https://api.deepseek.com/v1");
        String key = prefs().getString("apiKey", "");
        String model = prefs().getString("model", "deepseek-chat");

        showCard("问：生成中…", "答：", "", false);

        final String finalCtx = ctx;
        final String cacheKey = target;
        new Thread(() -> {
            QuickAnswerClient.Result r = client.ask(baseUrl, key, model, finalCtx, target, triggerContext);
            ui.post(() -> {
                if (!r.ok) {
                    showCard("问：没拿到答案", "答：" + r.error, "设置里填一下 API Key", false);
                    return;
                }
                if (cacheKey != null) answerCache.put(cacheKey, r.content);
                renderAnswer(r.content, r.costMs);
            });
        }).start();
    }

    /** 模型按「问/答/追」三行返回，宽松解析，缺行也能显示。 */
    private void renderAnswer(String content, long costMs) {
        String q = pick(content, "问");
        String a = pick(content, "答");
        String f = pick(content, "追");
        if (a == null) a = content;
        lastAnswer = a;
        showCard(q == null ? "问：" : "问：" + q, "答：" + a,
                (f == null ? "" : "追：" + f) + "   · " + (costMs / 1000.0) + "s", true);
    }

    private static String pick(String content, String label) {
        for (String line : content.split("\n")) {
            String t = line.trim();
            if (t.startsWith(label + "：") || t.startsWith(label + ":")) {
                return t.substring(2).trim();
            }
        }
        return null;
    }

    private void showCard(String q, String a, String f, boolean copyable) {
        cardBox.setVisibility(View.VISIBLE);
        cardQuestion.setText(q);
        cardAnswer.setText(a);
        cardFollow.setText(f);
    }

    private void appendTranscript(String text, boolean hit) {
        appendRow((SystemClock.elapsedRealtime() - sessionStart) / 1000, text, hit);
    }

    /**
     * 整课录音的转写用这个：时间戳取「这段音频在录音里的起点」，
     * 而不是「文字回来的时刻」。否则课后回看时，日志时间跟录音播放位置对不上。
     */
    private void appendTranscriptAt(long startSec, String text, boolean hit) {
        appendRow(startSec, text, hit);
    }

    private TextView appendRow(long s, String text, boolean hit) {
        TextView t = new TextView(this);
        t.setText(String.format(Locale.CHINA, "%02d:%02d  %s", s / 60, s % 60, text));
        t.setTextSize(13);
        t.setLineSpacing(0, 1.4f);
        t.setPadding(0, dp(3), 0, dp(3));
        if (hit) {
            t.setTextColor(Color.parseColor("#B47900"));
            t.setTypeface(Typeface.DEFAULT_BOLD);
        } else {
            t.setTextColor(Color.parseColor("#3A3A34"));
        }
        transcriptBox.addView(t);
        transcriptScroll.post(() -> transcriptScroll.fullScroll(View.FOCUS_DOWN));
        return t;
    }

    private void appendTranscript(String text) {
        appendTranscript(text, false);
    }

    private static String firstOf(Bundle b) {
        if (b == null) return null;
        ArrayList<String> list = b.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        if (list == null || list.isEmpty()) return null;
        return list.get(0);
    }

    private static String tail(String s, int n) {
        return s.length() <= n ? s : "…" + s.substring(s.length() - n);
    }

    /**
     * 压缩引擎名，列表里才看得清。
     *
     * <p>入参有两种：{@code "pkg/com.a.b.Cls"}（来自设置项）和只有类名的
     * {@code "com.a.b.Cls"}（来自 ServiceInfo.name）。两种都要处理，否则后者会被
     * 当成包名原样吐出来。
     */
    private static String shortComp(String flat) {
        if (flat == null || flat.isEmpty()) return "未设置";
        String pkg = "";
        String cls = flat;
        int slash = flat.indexOf('/');
        if (slash > 0) {
            pkg = flat.substring(0, slash);
            cls = flat.substring(slash + 1);
        }
        int i = cls.lastIndexOf('.');
        if (i > 0) cls = cls.substring(i + 1);
        return pkg.isEmpty() ? cls : pkg + "/" + cls;
    }

    // ---------------------------------------------------------------- 文本回放

    /**
     * 把三段真实转写稿按节奏喂进来。末尾追加一条模拟点名 ——
     * 你给的三段里一次点名都没有，不加这条就演示不出触发。
     */
    private void replay() {
        final String[] script = {
                "我们要用到的基本上我们涉一到神经网络这一块嘛，这个Python对Python语言比较多",
                "所以我们就是基于Python语言后面会讲一讲Python基础不多，不太合适讲就是说Python语言去看怎么去取这个数据。",
                "那么对于对于这个数据来说，如果说是它是一个6×3的这样一个数据，我们这里首先敲一下。",
                "6×3类地位数据，如果我们想取某一行，是怎么取它每个在每个维度上的数据的位置都是从0开始打标的，不是从一开始打标的。",
                "那么，如果我说想取某一行所有数据或某一列所有的数据，第一个维度的所有数据怎么取呢？",
                "怎么取呢？是这样取，0逗号，然后这个写成冒号，写个冒号。",
                "我们这个假设A就是这个张亮的话，我加一个冒号，这就取得第一行的所有，就代表这这一列所有的都都都选了。",
                "同样的，我要取第一列的所有数据，那就是冒号。冒号零对吧。",
                "那现在的问题，他说那我如果想取第一行到第3列的数据，第一行到第三行的数据，数据怎么办呢。",
                "这个呢，代表它and但这个and这个位置是不选的。",
                // ↓ 模拟点名，真实课堂里对应「来，请个同学说说」
                "好，那这个问题我们请一个同学来说一下，刚才那个取第一行到第三行的数据怎么取。",
        };

        stopListening();
        engine.reset();
        recent.clear();
        transcriptBox.removeAllViews();
        cardBox.setVisibility(View.GONE);
        preview.setVisibility(View.GONE);
        sessionStart = SystemClock.elapsedRealtime();
        status.setText("回放中 · 不含麦克风，纯文本");

        for (int i = 0; i < script.length; i++) {
            final String line = script[i];
            ui.postDelayed(() -> {
                appendTranscript(line);
                recent.add(line);
                while (recent.size() > 12) recent.remove(0);
                TriggerEngine.Hit hit = engine.feed(line, SystemClock.elapsedRealtime());
                if (hit != null && hit.level == TriggerEngine.Level.STRONG) {
                    onTriggered(hit, line);
                }
            }, i * 1400L);
        }
        ui.postDelayed(() -> status.setText("回放结束 · " + engine.summary()),
                script.length * 1400L + 200);
    }

    // ---------------------------------------------------------------- 诊断 / 问题池 / 设置

    /**
     * 自动挑一个识别引擎。
     *
     * <p>优先级（实测出来的，不是拍脑袋）：
     * <ol>
     *   <li><b>小米大脑</b> {@code com.xiaomi.mibrain.speech} —— 小米真机上唯一注册的 ASR，
     *       中文强，而且是系统级服务。很多小米机的「系统默认引擎」是空的，
     *       不显式指定就等于在跟空气说话。</li>
     *   <li><b>Android System Intelligence</b> {@code com.google.android.as} —— 原生机上
     *       真正干活的那个（Live Caption 用的就是它）。GTM 模拟器上默认居然是
     *       {@code com.google.android.tts}，那是个 TTS 应用顺带注册的，识别经常直接报网络错。</li>
     *   <li>其余随便取第一个。</li>
     * </ol>
     */
    private void buildCandidates() {
        candidates.clear();
        List<ResolveInfo> list = getPackageManager().queryIntentServices(
                new Intent("android.speech.RecognitionService"), 0);

        List<int[]> ranks = new ArrayList<>();
        List<ComponentName> names = new ArrayList<>();
        for (ResolveInfo ri : list) {
            ServiceInfo si = ri.serviceInfo;
            if (si == null) continue;
            int rank = 3;
            if (si.packageName.contains("mibrain")) {
                rank = 0;
            } else if ("com.google.android.as".equals(si.packageName)) {
                rank = 1;
            }
            ranks.add(new int[]{rank});
            names.add(new ComponentName(si.packageName, si.name));
        }

        // 按 rank 升序排（简单插入排序，候选通常只有 1~3 个）
        for (int i = 1; i < names.size(); i++) {
            int j = i - 1;
            ComponentName cn = names.get(i);
            int rk = ranks.get(i)[0];
            while (j >= 0 && ranks.get(j)[0] > rk) {
                names.set(j + 1, names.get(j));
                ranks.set(j + 1, ranks.get(j));
                j--;
            }
            names.set(j + 1, cn);
            ranks.set(j + 1, new int[]{rk});
        }

        candidates.addAll(names);
        candidateIndex = 0;
        engineComponent = candidates.isEmpty() ? null : candidates.get(0);
    }

    /**
     * 引擎连续失败时换下一个，而不是退回「系统默认」。
     *
     * <p>教训：小米机上「系统默认」是空的，退回默认等于退回一个必然失败的状态，
     * 结果就是 12 秒重启 1022 次、一个字都收不到。没得换时就保留当前引擎，
     * 靠 {@link #restart} 的退避和最小间隔撑着。
     */
    private void rotateCandidate() {
        if (candidates.size() <= 1) return;
        candidateIndex = (candidateIndex + 1) % candidates.size();
        engineComponent = candidates.get(candidateIndex);
        destroyRecognizer();
        toast("引擎连续失败，换到 " + engineName());
    }

    /** 错误码转可读名字，报告里靠它定位问题。 */
    private static String errorName(int e) {
        switch (e) {
            case 1: return "NETWORK_TIMEOUT";
            case 2: return "NETWORK";
            case 3: return "AUDIO";
            case 4: return "SERVER";
            case 5: return "CLIENT";
            case 6: return "SPEECH_TIMEOUT";
            case 7: return "NO_MATCH";
            case 8: return "RECOGNIZER_BUSY";
            case 9: return "INSUFFICIENT_PERMISSIONS";
            case 10: return "TOO_MANY_REQUESTS";
            case 11: return "SERVER_DISCONNECTED";
            case 12: return "LANGUAGE_NOT_SUPPORTED";
            case 13: return "LANGUAGE_UNAVAILABLE";
            default: return "CODE_" + e;
        }
    }

    /** 当前引擎的短名，状态行上显示。 */
    private String engineName() {
        if (engineComponent == null) return "系统默认";
        String pkg = engineComponent.getPackageName();
        if (pkg.contains("mibrain")) return "小米大脑";
        if (pkg.contains("google.android.as")) return "Google ASI";
        if (pkg.contains("google.android.tts")) return "Google TTS";
        return pkg;
    }

    /**
     * 当前引擎要求的权限 + 是否导出。
     *
     * <p>报 {@code INSUFFICIENT_PERMISSIONS} 时，光知道「缺权限」没用，得知道缺的是
     * 哪一个：如果是 {@code signature} / {@code signatureOrSystem} 级别，普通应用永远
     * 拿不到，这条路直接判死；如果只是普通权限，还有办法申请。
     */
    private String enginePermission() {
        if (engineComponent == null) return "—（跟随系统默认）";
        try {
            ServiceInfo si = getPackageManager().getServiceInfo(engineComponent, 0);
            String perm = si.permission == null ? "无（那就不该报权限错）" : si.permission;
            return perm + "  ·  exported=" + si.exported;
        } catch (Exception e) {
            return "读取失败（服务可能未导出）";
        }
    }

    /**
     * 识别引擎选择器。
     *
     * <p>这是回答「能不能用上小米自家模型」的唯一靠谱办法：把系统里真正注册了
     * {@code android.speech.RecognitionService} 的服务列出来。如果小米录音机
     * 那条转写管线没在这一列里，就说明第三方拿不到它 —— 再怎么调参数也没用。
     *
     * <p>选中的引擎会用 {@code createSpeechRecognizer(context, ComponentName)} 直接指定，
     * 绕开「系统默认」这个黑盒。
     */
    private void showEnginePicker() {
        List<ResolveInfo> list = getPackageManager().queryIntentServices(
                new Intent("android.speech.RecognitionService"), 0);

        String def = android.provider.Settings.Secure.getString(
                getContentResolver(), "voice_recognition_service");

        final List<ComponentName> comps = new ArrayList<>();
        final List<String> labels = new ArrayList<>();

        labels.add("系统默认  ·  " + shortComp(def));
        comps.add(null);

        for (ResolveInfo ri : list) {
            ServiceInfo si = ri.serviceInfo;
            if (si == null) continue;
            ComponentName cn = new ComponentName(si.packageName, si.name);
            comps.add(cn);
            String tag = (si.packageName.contains("miui") || si.packageName.contains("xiaomi"))
                    ? "  ← 小米" : "";
            labels.add(si.packageName + "\n" + shortComp(si.name) + tag);
        }

        if (labels.size() == 1) {
            toast("系统里没注册任何识别服务");
            return;
        }

        new android.app.AlertDialog.Builder(this)
                .setTitle("选识别引擎（共 " + (labels.size() - 1) + " 个）")
                .setItems(labels.toArray(new String[0]), (d, which) -> {
                    engineComponent = comps.get(which);
                    fastFailures = 0;
                    destroyRecognizer();
                    // 手动选的引擎也要同步到候选序列，否则失败轮换会跳回原来的
                    if (engineComponent != null) {
                        int idx = candidates.indexOf(engineComponent);
                        if (idx >= 0) candidateIndex = idx;
                    }
                    prefs().edit()
                            .putString("engine", engineComponent == null ? "" : engineComponent.flattenToString())
                            .putBoolean("engineSet", true)
                            .apply();
                    toast(engineComponent == null ? "已切回系统默认" : "已指定 " + engineName() + "，重新开始听生效");
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /**
     * 给真机上一节课用的体检表。
     *
     * <p>「估算空洞」按每次重启 120ms 算 —— 这是个保守下界，实际取决于引擎释放速度。
     */
    private void showDiagnostics() {
        long total = sessionStart == 0 ? 0 : (SystemClock.elapsedRealtime() - sessionStart) / 1000;
        long holeMs = (long) restarts * 120;
        String msg = String.join("\n",
                "本次时长：" + total + " 秒",
                "识别重启：" + restarts + " 次",
                "报错次数：" + errors + " 次",
                "收到字数：" + charsHeard + " 字",
                "平均每段：" + (restarts == 0 ? "—" : (total / restarts) + " 秒") + "（越长越好）",
                String.format(Locale.CHINA, "估算空洞：%.1f 秒（占 %.1f%%）",
                        holeMs / 1000.0, total == 0 ? 0 : holeMs * 100.0 / (total * 1000)),
                "",
                "当前引擎：" + (engineComponent == null ? "系统默认" : engineComponent.getPackageName()),
                "候选引擎：" + candidates.size() + " 个（当前第 "
                        + (candidates.isEmpty() ? 0 : candidateIndex + 1) + " 个）",
                "最近错误：" + (lastErrorCode == 0 ? "无" : errorName(lastErrorCode) + "(" + lastErrorCode + ")"),
                "引擎权限：" + enginePermission(),
                "紧密失败：" + fastFailures + " 次",
                "当前冷却：" + (engine.currentCooldown() / 1000) + " 秒（连续触发会自动拉长）",
                "答案缓存：" + answerCache.size() + " 条",
                "屏幕模式：" + (dimMode ? "暗屏（出卡亮 " + (FLASH_MS / 1000) + " 秒）" : "常亮"),
                "",
                "停顿判定：" + SILENCE_COMPLETE_MS + "ms（默认约 1000ms）",
                "识别服务：" + (SpeechRecognizer.isRecognitionAvailable(this) ? "可用" : "不可用"),
                "",
                "--- MiMo 云端转写 ---",
                "MiMo Key：" + (prefs().getString("mimoKey", "").trim().isEmpty() ? "未填（整课录音拿不到文字）" : "已填"),
                "整课录音：成功 " + RecordService.segDone + " 段 · 失败 " + RecordService.segFail
                        + " 段 · 积压丢弃 " + RecordService.droppedTotal + " 段",
                "累计 " + RecordService.charTotal + " 字 · " + (RecordService.audioMsTotal / 1000) + " 秒"
                        + String.format(Locale.CHINA, "（约 ¥%.2f）", RecordService.audioMsTotal / 3600000.0 * 0.5),
                RecordService.lastAsrError == null ? "转写暂无报错"
                        : "最近转写错误：" + RecordService.lastAsrError,
                "",
                "平均每段越接近 6 秒说明静音超时调对了；",
                "空洞占比超过 15% 就该考虑换流式 ASR。");

        new android.app.AlertDialog.Builder(this)
                .setTitle("诊断")
                .setMessage(msg)
                .setPositiveButton("知道了", null)
                .show();
    }

    private void showPool() {
        List<TriggerEngine.Question> pool = engine.questionPool(SystemClock.elapsedRealtime());
        if (pool.isEmpty()) {
            toast("问题池是空的：还没听够，或者这段老师没提问");
            return;
        }
        StringBuilder sb = new StringBuilder();
        for (TriggerEngine.Question q : pool) {
            sb.append("· ").append(q.text).append("\n\n");
        }
        new android.app.AlertDialog.Builder(this)
                .setTitle("这堂课问过什么（" + pool.size() + " 条）")
                .setMessage(sb.toString().trim())
                .setPositiveButton("知道了", null)
                .show();
    }

    private SharedPreferences prefs() {
        return getSharedPreferences(PREF, MODE_PRIVATE);
    }

    private void showSettings() {
        android.widget.ScrollView scroll = new android.widget.ScrollView(this);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(20), dp(12), dp(20), dp(4));

        // ---- 接话用的对话模型 ----
        EditText base = field("接口地址", prefs().getString("baseUrl", "https://api.deepseek.com/v1"));
        EditText key = field("API Key", prefs().getString("apiKey", ""));
        EditText model = field("模型", prefs().getString("model", "deepseek-chat"));
        box.addView(sectionTitle("接话（对话模型）"));
        box.addView(label("接口地址（OpenAI 兼容）"));
        box.addView(base);
        box.addView(label("API Key"));
        box.addView(key);
        box.addView(label("模型"));
        box.addView(model);

        // ---- MiMo 语音识别 ----
        EditText mBase = field(MimoAsrClient.DEFAULT_BASE, prefs().getString("mimoBase", MimoAsrClient.DEFAULT_BASE));
        EditText mKey = field("MiMo API Key（mimo_...）", prefs().getString("mimoKey", ""));
        EditText mModel = field(MimoAsrClient.DEFAULT_MODEL, prefs().getString("mimoModel", MimoAsrClient.DEFAULT_MODEL));
        box.addView(sectionTitle("语音识别（小米 MiMo 云端）"));
        box.addView(label("接口地址"));
        box.addView(mBase);
        box.addView(label("MiMo API Key"));
        box.addView(mKey);
        box.addView(label("模型"));
        box.addView(mModel);
        box.addView(label("计费 ¥0.5/小时 · 一节课约 ¥0.38 · 密钥在 platform.xiaomimimo.com 申请"));

        scroll.addView(box);

        new android.app.AlertDialog.Builder(this)
                .setTitle("设置")
                .setView(scroll)
                .setPositiveButton("保存", (d, w) -> {
                    prefs().edit()
                            .putString("baseUrl", base.getText().toString().trim())
                            .putString("apiKey", key.getText().toString().trim())
                            .putString("model", model.getText().toString().trim())
                            .putString("mimoBase", mBase.getText().toString().trim())
                            .putString("mimoKey", mKey.getText().toString().trim())
                            .putString("mimoModel", mModel.getText().toString().trim())
                            .apply();
                    toast("已保存");
                })
                .setNegativeButton("取消", null)
                .show();
    }

    /** 设置面板里的小节标题。 */
    private TextView sectionTitle(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(15);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setTextColor(Color.parseColor("#1A1A1A"));
        t.setPadding(0, dp(18), 0, dp(4));
        return t;
    }

    private TextView label(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(12);
        t.setTextColor(Color.parseColor("#8A8A80"));
        t.setPadding(0, dp(10), 0, dp(2));
        return t;
    }

    private EditText field(String hint, String value) {
        EditText e = new EditText(this);
        e.setHint(hint);
        e.setText(value);
        e.setTextSize(14);
        e.setSingleLine(true);
        e.setInputType(InputType.TYPE_CLASS_TEXT);
        return e;
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 回到界面就把回调挂上；服务可能在后台已经跑了一会儿
        RecordService.listener = recordListener;
        if (recordChip != null) styleChip(recordChip, RecordService.running);
    }

    @Override
    protected void onPause() {
        super.onPause();
        // 界面不在了就松开回调，避免泄漏。录音服务本身继续跑，锁屏也不停。
        RecordService.listener = null;
    }

    @Override
    protected void onDestroy() {
        stopListening();
        ui.removeCallbacksAndMessages(null);
        super.onDestroy();
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] grant) {
        super.onRequestPermissionsResult(code, perms, grant);
        if (code == REQ_AUDIO && grant.length > 0 && grant[0] == PackageManager.PERMISSION_GRANTED) {
            if (pendingRecordStart) {
                pendingRecordStart = false;
                startLectureRecord();
            } else {
                startListening();
            }
        }
    }
}
