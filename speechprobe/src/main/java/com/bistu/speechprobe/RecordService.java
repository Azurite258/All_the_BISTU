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
    private static final int NOTI_ID = 0x201;

    /** 录音过程的回调。Activity 挂在 onResume / onPause 上，避免泄漏。 */
    public interface Listener {
        void onLevel(int peak, long seconds);

        void onFinished(String path, long seconds, long bytes, String error);
    }

    public static volatile Listener listener;
    public static volatile boolean running;
    public static volatile long startedAtMs;

    private AudioRecord record;
    private Thread worker;
    private PowerManager.WakeLock wakeLock;
    private RandomAccessFile out;
    private File outFile;
    private long pcmBytes;

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

    // ---------------------------------------------------------------- 录音

    private void startRecording() {
        if (running) return;

        try {
            File dir = getExternalFilesDir(null);
            if (dir == null) dir = getFilesDir();
            outFile = new File(dir, "lecture_" + System.currentTimeMillis() + ".wav");
            out = new RandomAccessFile(outFile, "rw");
            out.setLength(0);
            out.write(new byte[44]); // 先占位，结束时回填真实长度
            pcmBytes = 0;
        } catch (Exception e) {
            finish(null, 0, 0, "创建文件失败：" + e.getMessage());
            stopSelf();
            return;
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
