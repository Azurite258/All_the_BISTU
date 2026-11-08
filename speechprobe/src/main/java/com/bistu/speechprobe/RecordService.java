package com.bistu.speechprobe;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.SystemClock;

import java.io.File;
import java.io.RandomAccessFile;

/**
 * 整节课录音服务。
 *
 * <p>为什么要有它：系统 {@code SpeechRecognizer} 在小米上被签名权限挡死，任何替代方案
 * （云 ASR 流式、本地模型）都需要一块「稳定拿到 16k PCM 音频」的地基。这个服务就负责那件事：
 * 前台服务常驻 + 唤醒锁 + 写 WAV，和识别引擎完全解耦。
 *
 * <p>把它做在 Service 里而不是 Activity 里，是因为一节课 45 分钟，Activity 随时可能被
 * 系统回收或切到后台。前台服务带常驻通知，是被允许长期运行的唯一形态。
 *
 * <p>Android 14 起麦克风类型的前台服务必须声明
 * {@code foregroundServiceType="microphone"} 并申请
 * {@code FOREGROUND_SERVICE_MICROPHONE}，否则启动就崩。
 */
public class RecordService extends Service {

    public static final String ACTION_START = "com.bistu.speechprobe.REC_START";
    public static final String ACTION_STOP = "com.bistu.speechprobe.REC_STOP";

    private static final String CHANNEL = "classroom_rec";
    private static final String CHANNEL_ALERT = "classroom_hit";
    private static final int NOTI_ID = 0x201;
    private static final int NOTI_ALERT = 0x202;

    /** 录音过程的回调。Activity 挂在 onResume / onPause 上，避免泄漏。 */
    public interface Listener {
        void onLevel(int peak, long seconds);

        void onFinished(String path, long seconds, long bytes, String error);

        /** 某一段转写回来了。失败时 text 为 null，err 有原因。startSec 是这段音频在录音里的起点。 */
        void onTranscript(int segIndex, long startSec, String text, String err, long costMs);

        /** 检测到点名。锁屏时也会走这里，界面在不在都能收到。 */
        void onHit(TriggerEngine.Hit hit, String text);
    }

    /**
     * 每段 12 秒。
     *
     * <p>这个数字是延迟和稳定性的折中：30 秒一段意味着老师点名后，能拿到的最新转写
     * 可能是半分钟前的，问句早就过去了；12 秒能把「转写到位」的延迟压到 15 秒左右，
     * 配合问题池（老师自问自答的句子早就进池了）够用。
     *
     * <p>再加长就会超过 10MB 上限（16k/16bit 约 42.7KB/秒，编码后），12 秒只有约 512KB，余量充足。
     */
    private static final int SEG_PCM_BYTES = 16000 * 2 * 12;

    /** 允许排队的段数。超了就把最老的扔掉——积压只会让答案越来越过期。 */
    private static final int MAX_PENDING_SEGMENTS = 4;

    public static volatile Listener listener;
    public static volatile boolean running;
    public static volatile long startedAtMs;

    // 转写统计。界面切走时监听器会被摘掉，回来靠这些静态值把数字接上。
    public static volatile int segDone;
    public static volatile int segFail;
    public static volatile long charTotal;
    public static volatile long audioMsTotal;
    public static volatile String lastTranscript;
    public static volatile String lastAsrError;
    /** 转写文本落盘路径，下课后可以直接取走。 */
    public static volatile String textPath;

    private AudioRecord record;
    private Thread worker;
    private PowerManager.WakeLock wakeLock;
    private RandomAccessFile out;
    private File outFile;
    private long pcmBytes;

    // ---- 分段转写 ----
    /** 当前正在累积的 PCM。录音线程写、上传线程取走，用对象锁保护。 */
    private final java.io.ByteArrayOutputStream segBuf = new java.io.ByteArrayOutputStream();
    private MimoAsrClient asr;
    /** 单线程：保证上传和落盘按时间顺序，转写结果不会串行错位。 */
    private java.util.concurrent.ExecutorService asrPool;
    private TriggerEngine engine;
    private File textFile;
    private java.io.Writer textOut;
    private int segIndex = 0;
    private int hitCount = 0;
    /** 已提交但还没跑完的段数。队列太长就丢老段，否则转写会越拖越落后。 */
    private java.util.concurrent.atomic.AtomicInteger pending = new java.util.concurrent.atomic.AtomicInteger();
    /** 已经送去转写的 PCM 字节数，用来算每段在录音里的起点（用于时间戳）。 */
    private long sentPcmBytes = 0;
    /** 因为积压被丢掉的段数，诊断里要看。 */
    public static volatile int droppedTotal = 0;

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (ACTION_STOP.equals(action)) {
            stopRecording();
            return START_NOT_STICKY;
        }
        if (ACTION_START.equals(action)) {
            ensureChannel();
            // 必须先 startForeground 再碰麦克风，否则 Android 14+ 直接抛 SecurityException
            startForegroundCompat();
            startRecording();
        }
        return START_STICKY;
    }

    // ---------------------------------------------------------------- 前台化

    private void ensureChannel() {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm == null) return;
        NotificationChannel ch = new NotificationChannel(
                CHANNEL, "课堂录音", NotificationManager.IMPORTANCE_LOW);
        ch.setDescription("整节课录音正在运行");
        ch.setShowBadge(false);
        nm.createNotificationChannel(ch);
    }

    private void startForegroundCompat() {
        Intent open = new Intent(this, ClassroomActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Intent stop = new Intent(this, RecordService.class).setAction(ACTION_STOP);
        PendingIntent stopPi = PendingIntent.getService(this, 1, stop,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification n = new Notification.Builder(this, CHANNEL)
                .setContentTitle("课堂录音中")
                .setContentText("正在采集音频，点按返回应用")
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setOngoing(true)
                .setContentIntent(pi)
                .addAction(new Notification.Action.Builder(null, "停止", stopPi).build())
                .build();

        startForeground(NOTI_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
    }

    private void updateNotification(long seconds, int peak) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm == null) return;
        Notification n = new Notification.Builder(this, CHANNEL)
                .setContentTitle("课堂录音中 · " + (seconds / 60) + ":" + String.format("%02d", seconds % 60))
                .setContentText("电平 " + peak + (peak < 20 ? "（很安静）" : ""))
                .setSmallIcon(android.R.drawable.ic_btn_speak_now)
                .setOngoing(true)
                .build();
        nm.notify(NOTI_ID, n);
    }

    // ---------------------------------------------------------------- 分段转写

    /** 把当前累积的 PCM 取走并送去转写。取走后立刻开始攒下一段。 */
    private void flushSegment() {
        byte[] chunk;
        long startSec;
        synchronized (segBuf) {
            if (segBuf.size() < 3200) return; // 不足 0.1 秒，丢掉
            chunk = segBuf.toByteArray();
            segBuf.reset();
            // 32000 字节 = 1 秒（16k × 16bit × 单声道）
            startSec = sentPcmBytes / 32000L;
            sentPcmBytes += chunk.length;
        }
        submitAsr(chunk, startSec);
    }

    /**
     * 上传一段音频并在拿到文本后做三件事：落盘、喂触发引擎、通知界面。
     *
     * <p>为什么要放在 Service 而不是 Activity：上课时手机是锁屏的，
     * Activity 早被系统回收了，只有前台服务还活着。点名要在锁屏时也能被听到。
     */
    private void submitAsr(byte[] pcm, long startSec) {
        if (asrPool == null || asr == null) return;

        // 网慢的时候上传会排队。队列一长，转写就严重落后于现实，不如直接丢掉最老的。
        if (pending.get() >= MAX_PENDING_SEGMENTS) {
            droppedTotal++;
            return;
        }

        final int idx = ++segIndex;
        // 捕获当前会话的写句柄：万一下一课马上开始，旧任务不能再往新文件里写
        final java.io.Writer writer = textOut;
        pending.incrementAndGet();
        try {
            asrPool.execute(() -> {
                try {
                    String base = prefs().getString("mimoBase", MimoAsrClient.DEFAULT_BASE);
                    String key = prefs().getString("mimoKey", "");
                    String model = prefs().getString("mimoModel", MimoAsrClient.DEFAULT_MODEL);

                    MimoAsrClient.Result r = asr.transcribe(pcm, base, key, model);

                    if (!r.ok) {
                        segFail++;
                        lastAsrError = r.error;
                        notifyTranscript(idx, startSec, null, r.error, r.costMs);
                        return;
                    }

                    // 只有成功才计费。失败的请求服务端没转写，不该算进钱里。
                    audioMsTotal += r.audioMs;
                    segDone++;
                    charTotal += r.text.length();
                    lastTranscript = r.text;
                    appendText(writer, startSec, r.text);
                    notifyTranscript(idx, startSec, r.text, null, r.costMs);

                    // 触发检测放在转写之后、同一线程里，顺序天然是对的
                    TriggerEngine.Hit hit = engine.feed(r.text, System.currentTimeMillis());
                    if (hit != null && hit.level == TriggerEngine.Level.STRONG) {
                        onStrongHit(hit, r.text);
                    }
                } finally {
                    pending.decrementAndGet();
                }
            });
        } catch (Exception ignored) {
            // shutdown 之后还在提交，忽略即可
            pending.decrementAndGet();
        }
    }

    private void onStrongHit(TriggerEngine.Hit hit, String text) {
        hitCount++;
        vibrate();
        notifyHit(hit);
        Listener l = listener;
        if (l != null) l.onHit(hit, text);
    }

    /** 点名时震两下。比响铃隐蔽，课桌上放着也能感觉到。 */
    private void vibrate() {
        try {
            android.os.Vibrator v = (android.os.Vibrator) getSystemService(VIBRATOR_SERVICE);
            if (v == null || !v.hasVibrator()) return;
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                v.vibrate(android.os.VibrationEffect.createWaveform(
                        new long[]{0, 180, 120, 180}, -1));
            } else {
                v.vibrate(new long[]{0, 180, 120, 180}, -1);
            }
        } catch (Exception ignored) {
        }
    }

    private void notifyTranscript(int idx, long startSec, String text, String err, long costMs) {
        Listener l = listener;
        if (l != null) {
            try {
                l.onTranscript(idx, startSec, text, err, costMs);
            } catch (Exception ignored) {
            }
        }
    }

    private void notifyHit(TriggerEngine.Hit hit) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm == null) return;
        ensureAlertChannel(nm);
        Intent open = new Intent(this, ClassroomActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 2, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification n = new Notification.Builder(this, CHANNEL_ALERT)
                .setContentTitle("可能点名了 · " + hit.word)
                .setContentText(hit.target == null ? "问题池里还没有可用的问句" : "目标：" + hit.target)
                .setSmallIcon(android.R.drawable.ic_dialog_alert)
                .setPriority(Notification.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(pi)
                .build();
        nm.notify(NOTI_ALERT, n);
    }

    private void ensureAlertChannel(NotificationManager nm) {
        if (android.os.Build.VERSION.SDK_INT < 26) return;
        NotificationChannel ch = new NotificationChannel(
                CHANNEL_ALERT, "点名提醒", NotificationManager.IMPORTANCE_HIGH);
        ch.setDescription("检测到老师点名时提醒");
        nm.createNotificationChannel(ch);
    }

    /** 转写稿按段落追加，带「这段音频的起点」时间戳，下课直接能读、也能对上录音位置。 */
    private void appendText(java.io.Writer w, long startSec, String text) {
        if (w == null) return;
        try {
            synchronized (w) {
                w.write(String.format(java.util.Locale.CHINA,
                        "[%02d:%02d] %s%n", startSec / 60, startSec % 60, text));
                w.flush();
            }
        } catch (Exception ignored) {
        }
    }

    private android.content.SharedPreferences prefs() {
        // 必须和 ClassroomActivity.PREF 一致，否则界面上填的 Key 服务读不到
        return getSharedPreferences("ai", MODE_PRIVATE);
    }

    // ---------------------------------------------------------------- 录音

    private void startRecording() {
        if (running) return;

        try {
            File dir = getExternalFilesDir(null);
            if (dir == null) dir = getFilesDir();
            String stamp = String.valueOf(System.currentTimeMillis());
            outFile = new File(dir, "lecture_" + stamp + ".wav");
            out = new RandomAccessFile(outFile, "rw");
            out.setLength(0);
            out.write(new byte[44]); // 先占位，结束时回填真实长度
            pcmBytes = 0;

            // 转写文本与录音同名，方便对着一节课找它的稿子
            textFile = new File(dir, "lecture_" + stamp + ".txt");
            textOut = new java.io.OutputStreamWriter(
                    new java.io.FileOutputStream(textFile, true), java.nio.charset.StandardCharsets.UTF_8);
            textPath = textFile.getAbsolutePath();
        } catch (Exception e) {
            finish(null, 0, 0, "创建文件失败：" + e.getMessage());
            stopSelf();
            return;
        }

        synchronized (segBuf) {
            segBuf.reset();
        }
        sentPcmBytes = 0;
        pending.set(0);
        segDone = 0;
        segFail = 0;
        charTotal = 0;
        audioMsTotal = 0;
        droppedTotal = 0;
        lastTranscript = null;
        lastAsrError = null;
        segIndex = 0;
        hitCount = 0;
        engine = new TriggerEngine();
        asr = new MimoAsrClient();
        if (asrPool == null || asrPool.isShutdown()) {
            asrPool = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "mimo-asr");
                t.setDaemon(true);
                return t;
            });
        }

        acquireWakeLock();
        running = true;
        startedAtMs = SystemClock.elapsedRealtime();

        final int rate = 16000;
        final int minBuf = Math.max(3200, AudioRecord.getMinBufferSize(
                rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT));
        final File target = outFile;

        worker = new Thread(() -> {
            String error = null;
            long startAt = SystemClock.elapsedRealtime();
            try {
                // MIC 而不是 VOICE_RECOGNITION：后者会开系统降噪，把远场声音压得太狠
                record = new AudioRecord(MediaRecorder.AudioSource.MIC, rate,
                        AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, minBuf * 4);
                if (record.getState() != AudioRecord.STATE_INITIALIZED) {
                    error = "麦克风初始化失败（被占用或缺权限）";
                } else {
                    record.startRecording();
                    short[] buf = new short[1600]; // 100ms @16k
                    long nextUi = 0;
                    int peak = 0;

                    while (running) {
                        int r = record.read(buf, 0, buf.length);
                        if (r <= 0) continue;
                        byte[] bytes = new byte[r * 2];
                        for (int i = 0; i < r; i++) {
                            short v = buf[i];
                            int a = Math.abs(v);
                            if (a > peak) peak = a;
                            bytes[i * 2] = (byte) (v & 0xFF);
                            bytes[i * 2 + 1] = (byte) ((v >> 8) & 0xFF);
                        }
                        out.write(bytes);
                        pcmBytes += bytes.length;

                        // 攒够一段就送去转写。上传在另一个线程，这里不阻塞录音。
                        boolean full;
                        synchronized (segBuf) {
                            segBuf.write(bytes, 0, bytes.length);
                            full = segBuf.size() >= SEG_PCM_BYTES;
                        }
                        if (full) flushSegment();

                        long now = SystemClock.elapsedRealtime();
                        if (now >= nextUi) {
                            nextUi = now + 500;
                            long secs = (now - startAt) / 1000;
                            updateNotification(secs, peak);
                            Listener l = listener;
                            if (l != null) l.onLevel(peak, secs);
                            peak = 0;
                        }
                    }
                }
            } catch (Exception e) {
                error = e.getClass().getSimpleName() + "：" + e.getMessage();
            } finally {
                releaseRecording();
                long secs = (SystemClock.elapsedRealtime() - startAt) / 1000;
                finish(target == null ? null : target.getAbsolutePath(), secs, pcmBytes, error);
                stopSelf();
            }
        }, "lecture-record");
        worker.start();
    }

    private void stopRecording() {
        running = false;
        if (worker != null) {
            try {
                worker.join(1500);
            } catch (InterruptedException ignored) {
            }
            worker = null;
        } else {
            releaseRecording();
            finish(outFile == null ? null : outFile.getAbsolutePath(), 0, pcmBytes, null);
            stopSelf();
        }
    }

    private void releaseRecording() {
        if (record != null) {
            try {
                record.stop();
            } catch (Exception ignored) {
            }
            try {
                record.release();
            } catch (Exception ignored) {
            }
            record = null;
        }
    }

    /** 回填 WAV 头，然后把结果交给界面。 */
    private void finish(String path, long seconds, long bytes, String error) {
        running = false;

        // 最后不足一整段的尾巴也要转，否则一节课末尾的话全丢
        flushSegment();

        try {
            if (out != null) {
                out.seek(0);
                out.write(wavHeader((int) (bytes & 0x7FFFFFFF), 16000));
                out.close();
                out = null;
            }
        } catch (Exception e) {
            if (error == null) error = "写入 WAV 头失败：" + e.getMessage();
        }
        try {
            if (textOut != null) {
                textOut.flush();
                textOut.close();
                textOut = null;
            }
        } catch (Exception ignored) {
        }
        // 已提交的段继续跑完（daemon 线程），新的提交会被拒绝
        if (asrPool != null) {
            asrPool.shutdown();
            asrPool = null;
        }

        releaseWakeLock();
        Listener l = listener;
        if (l != null) l.onFinished(path, seconds, bytes, error);
        stopForegroundCompat();
    }

    private static byte[] wavHeader(int pcmLen, int rate) {
        int channels = 1, bits = 16;
        int byteRate = rate * channels * bits / 8;
        java.nio.ByteBuffer h = java.nio.ByteBuffer.allocate(44)
                .order(java.nio.ByteOrder.LITTLE_ENDIAN);
        h.put("RIFF".getBytes());
        h.putInt(36 + pcmLen);
        h.put("WAVE".getBytes());
        h.put("fmt ".getBytes());
        h.putInt(16);
        h.putShort((short) 1);
        h.putShort((short) channels);
        h.putInt(rate);
        h.putInt(byteRate);
        h.putShort((short) (channels * bits / 8));
        h.putShort((short) bits);
        h.put("data".getBytes());
        h.putInt(pcmLen);
        return h.array();
    }

    private void stopForegroundCompat() {
        try {
            stopForeground(STOP_FOREGROUND_REMOVE);
        } catch (Exception ignored) {
        }
        NotificationManager nm = getSystemService(NotificationManager.class);
        if (nm != null) nm.cancel(NOTI_ID);
    }

    private void acquireWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) return;
        PowerManager pm = getSystemService(PowerManager.class);
        if (pm == null) return;
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "classroom:record");
        wakeLock.setReferenceCounted(false);
        wakeLock.acquire(4 * 60 * 60 * 1000L); // 上限 4 小时，够连上两节课
    }

    private void releaseWakeLock() {
        if (wakeLock != null && wakeLock.isHeld()) wakeLock.release();
        wakeLock = null;
    }

    @Override
    public void onDestroy() {
        running = false;
        releaseRecording();
        releaseWakeLock();
        super.onDestroy();
    }
}
