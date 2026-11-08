package com.bistu.allthebistu.classroom;

import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/**
 * 小米 MiMo 云端语音识别（mimo-v2.5-asr）。
 *
 * <p>为什么需要它：设备端的小米 {@code mibrain} 识别服务被签名级权限挡死
 * （{@code com.xiaomi.mibrain.speech.permission.BIND_SPEECH_RECOGNITION_SERVICE}），
 * 第三方 App 拿不到。而 MiMo 是<b>开放平台</b>，注册拿 Key 就能调，跟系统权限无关。
 *
 * <p>接口形态（官方文档）：走 OpenAI 兼容的 {@code /chat/completions}，
 * 音频不是走传统的 {@code /audio/transcriptions}，而是当作<b>多模态消息</b>塞进
 * {@code content} 数组，用 {@code input_audio} 传 data URL。
 *
 * <pre>
 * POST https://api.xiaomimimo.com/v1/chat/completions
 * api-key: $MIMO_API_KEY
 * {
 *   "model": "mimo-v2.5-asr",
 *   "messages": [{"role":"user","content":[
 *       {"type":"input_audio","input_audio":{"data":"data:audio/wav;base64,..."}}
 *   ]}],
 *   "asr_options": {"language": "zh"}
 * }
 * </pre>
 *
 * <p><b>重要限制</b>：base64 编码后不能超过 10MB，只支持 wav/mp3。
 * 16k/16bit/单声道约 42.7KB/秒（编码后），所以单次最多约 4 分钟。
 * 整节课必须分段，这个类只负责「一段 PCM → 一段文本」，
 * 分段调度在 {@link RecordService} 里。
 *
 * <p>计费按音频时长：¥0.5/小时，一节课 45 分钟约 ¥0.38。
 */
final class MimoAsrClient {

    static final String DEFAULT_BASE = "https://api.xiaomimimo.com/v1";
    static final String DEFAULT_MODEL = "mimo-v2.5-asr";

    /** 文档写的是 base64 后不超过 10MB。留 15% 余量，避免卡在边界上被拒。 */
    static final int MAX_ENCODED_BYTES = (int) (10 * 1024 * 1024 * 0.85);

    /** 按 16k/16bit/单声道算，一段最多能有多长（毫秒）。纯理论值，给界面提示用。 */
    static final int MAX_SEGMENT_MS = 3 * 60 * 1000;

    static final class Result {
        final boolean ok;
        final String text;
        final String error;
        final long costMs;
        /** 音频时长（毫秒），用来算花了多少钱。 */
        final long audioMs;

        Result(boolean ok, String text, String error, long costMs, long audioMs) {
            this.ok = ok;
            this.text = text;
            this.error = error;
            this.costMs = costMs;
            this.audioMs = audioMs;
        }
    }

    /**
     * 转写一段 16k / 16bit / 单声道 PCM。阻塞调用，调用方放子线程。
     *
     * @param pcm 裸 PCM（不含 WAV 头）
     */
    Result transcribe(byte[] pcm, String baseUrl, String apiKey, String model) {
        long t0 = System.currentTimeMillis();
        long audioMs = pcm == null ? 0 : pcm.length / 32L; // 32 字节 = 1 毫秒（16k×16bit×1ch）
        if (audioMs < 200) {
            return new Result(false, null, "音频太短（" + audioMs + "ms），跳过", 0, audioMs);
        }
        if (apiKey == null || apiKey.trim().isEmpty()) {
            return new Result(false, null, "还没填 MiMo API Key", 0, audioMs);
        }

        try {
            byte[] wav = wrapWav(pcm, 16000);
            String b64 = Base64.encodeToString(wav, Base64.NO_WRAP);
            if (b64.length() > MAX_ENCODED_BYTES) {
                return new Result(false, null,
                        "这段音频编码后 " + (b64.length() / 1024 / 1024) + "MB，超过 10MB 上限，需要切得更短",
                        0, audioMs);
            }

            JSONObject audio = new JSONObject();
            audio.put("data", "data:audio/wav;base64," + b64);

            JSONObject part = new JSONObject();
            part.put("type", "input_audio");
            part.put("input_audio", audio);

            JSONArray content = new JSONArray().put(part);

            JSONObject msg = new JSONObject();
            msg.put("role", "user");
            msg.put("content", content);

            JSONObject asrOptions = new JSONObject();
            // zh 比 auto 稳：课堂里偶尔冒英文单词，auto 会在中途切语种，切完那段容易丢字
            asrOptions.put("language", "zh");

            JSONObject body = new JSONObject();
            body.put("model", model == null || model.trim().isEmpty() ? DEFAULT_MODEL : model.trim());
            body.put("messages", new JSONArray().put(msg));
            body.put("asr_options", asrOptions);

            String root = normalizeBase(baseUrl);
            HttpURLConnection c = (HttpURLConnection) new URL(root + "/chat/completions").openConnection();
            c.setRequestMethod("POST");
            c.setConnectTimeout(15000);
            // 3 分钟音频的推理可能要几十秒，读超时给足
            c.setReadTimeout(120000);
            c.setDoOutput(true);
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            // 官方同时支持 api-key 头和 Bearer，两个都带上最省心
            c.setRequestProperty("api-key", apiKey.trim());
            c.setRequestProperty("Authorization", "Bearer " + apiKey.trim());

            try (OutputStream os = c.getOutputStream()) {
                os.write(body.toString().getBytes(StandardCharsets.UTF_8));
            }

            int code = c.getResponseCode();
            InputStream is = code >= 200 && code < 300 ? c.getInputStream() : c.getErrorStream();
            String resp = readAll(is);
            long cost = System.currentTimeMillis() - t0;

            if (code < 200 || code >= 300) {
                return new Result(false, null, "HTTP " + code + "：" + brief(resp), cost, audioMs);
            }

            String text = extractText(resp);
            if (text == null) {
                return new Result(false, null, "返回里没有文本：" + brief(resp), cost, audioMs);
            }
            return new Result(true, text, null, cost, audioMs);

        } catch (Exception e) {
            return new Result(false, null,
                    e.getClass().getSimpleName() + "：" + e.getMessage(),
                    System.currentTimeMillis() - t0, audioMs);
        }
    }

    /** 从 {@code choices[0].message.content} 取文本。content 可能是字符串，也可能是分片数组。 */
    private static String extractText(String resp) {
        try {
            JSONObject o = new JSONObject(resp);
            JSONArray choices = o.optJSONArray("choices");
            if (choices == null || choices.length() == 0) return null;
            JSONObject msg = choices.getJSONObject(0).optJSONObject("message");
            if (msg == null) return null;
            Object content = msg.opt("content");
            if (content instanceof String) {
                String s = ((String) content).trim();
                return s.isEmpty() ? null : s;
            }
            if (content instanceof JSONArray) {
                StringBuilder sb = new StringBuilder();
                JSONArray arr = (JSONArray) content;
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject p = arr.optJSONObject(i);
                    if (p == null) continue;
                    String t = p.optString("text", "");
                    if (!t.isEmpty()) sb.append(t);
                }
                String s = sb.toString().trim();
                return s.isEmpty() ? null : s;
            }
            return null;
        } catch (Exception e) {
            return null;
        }
    }

    private static String normalizeBase(String baseUrl) {
        String b = baseUrl == null || baseUrl.trim().isEmpty() ? DEFAULT_BASE : baseUrl.trim();
        while (b.endsWith("/")) b = b.substring(0, b.length() - 1);
        return b;
    }

    private static String readAll(InputStream is) throws Exception {
        if (is == null) return "";
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            char[] buf = new char[4096];
            int n;
            while ((n = r.read(buf)) > 0) sb.append(buf, 0, n);
        }
        return sb.toString();
    }

    private static String brief(String s) {
        if (s == null) return "";
        String t = s.replace('\n', ' ').trim();
        return t.length() <= 300 ? t : t.substring(0, 300) + "…";
    }

    /** 给裸 PCM 套一个 44 字节 WAV 头。 */
    static byte[] wrapWav(byte[] pcm, int rate) {
        int channels = 1, bits = 16;
        int byteRate = rate * channels * bits / 8;
        ByteBuffer h = ByteBuffer.allocate(44 + pcm.length).order(ByteOrder.LITTLE_ENDIAN);
        h.put("RIFF".getBytes(StandardCharsets.US_ASCII));
        h.putInt(36 + pcm.length);
        h.put("WAVE".getBytes(StandardCharsets.US_ASCII));
        h.put("fmt ".getBytes(StandardCharsets.US_ASCII));
        h.putInt(16);
        h.putShort((short) 1);
        h.putShort((short) channels);
        h.putInt(rate);
        h.putInt(byteRate);
        h.putShort((short) (channels * bits / 8));
        h.putShort((short) bits);
        h.put("data".getBytes(StandardCharsets.US_ASCII));
        h.putInt(pcm.length);
        h.put(pcm);
        return h.array();
    }

    /**
     * 从完整 WAV 文件里剥出裸 PCM。整课录音存的是标准 WAV，
     * 要分块上传就得先找到 data 块的位置。
     *
     * @return 裸 PCM，失败返回 null
     */
    static byte[] stripWavHeader(byte[] wav) {
        if (wav == null || wav.length < 44) return null;
        // 逐块扫，"data" 块不一定正好在 36 偏移（可能前面有 LIST 等附加块）
        int p = 12;
        while (p + 8 <= wav.length) {
            String id = new String(wav, p, 4, StandardCharsets.US_ASCII);
            int size = ByteBuffer.wrap(wav, p + 4, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
            if ("data".equals(id)) {
                int start = p + 8;
                int len = Math.min(size, wav.length - start);
                if (len <= 0) return null;
                byte[] pcm = new byte[len];
                System.arraycopy(wav, start, pcm, 0, len);
                return pcm;
            }
            if (size < 0) break;
            p += 8 + size + (size & 1);
        }
        // 扫不到就退回固定偏移
        byte[] pcm = new byte[wav.length - 44];
        System.arraycopy(wav, 44, pcm, 0, pcm.length);
        return pcm;
    }
}
