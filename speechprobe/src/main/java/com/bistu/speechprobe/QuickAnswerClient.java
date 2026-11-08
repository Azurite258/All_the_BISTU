package com.bistu.speechprobe;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * 接话生成。走 OpenAI 兼容协议，和主 App 的 AiClient 同一套接口，
 * 但这里不带 okhttp / 序列化库，纯 HttpURLConnection + org.json，模块保持零依赖。
 */
final class QuickAnswerClient {

    static final class Result {
        final boolean ok;
        final String content;
        final String error;
        final long costMs;

        Result(boolean ok, String content, String error, long costMs) {
            this.ok = ok;
            this.content = content;
            this.error = error;
            this.costMs = costMs;
        }
    }

    /**
     * 系统提示词。三条硬约束，都是为了「看一眼就能念」：
     * 短、口语、必须能直接说出口。
     */
    static final String SYSTEM = String.join("\n",
            "你在帮一个大学生应付课堂提问。你能看到的只有老师说话的语音转写稿：",
            "里面有错字、有重复、标点是按停顿硬插的、专业词经常被听错。",
            "",
            "输出严格三行，不要任何多余的字：",
            "问：<老师实际在问什么，一句话，20 字以内。他要是只在讲、没在问，就写「没在问，讲到这就行」>",
            "答：<他要是点名叫你，你能直接念出来的话。口语，2~3 句，80 字以内，要具体，能给例子或数字就给>",
            "追：<如果被追问一句「为什么」，再补一句怎么答>",
            "",
            "硬规则：",
            "1. 转写有错字，例如「张亮」其实是「张量」、「and」其实是「end」、「6存3」是「6×3」、",
            "   「打标」是「下标」、「倒数第三行」可能是「到第三行」。按上下文还原，别把错字当真。",
            "2. 不知道就写「这个没讲清楚，我只记得……」，不许编造老师没讲过的内容。",
            "3. 别提「这个问题」「首先其次」「综上所述」「综上」这类词，也别提你是 AI。",
            "4. 不要重复转写原文，直接给能说出口的答案。");

    /** 生成。阻塞调用，调用方放子线程。 */
    Result ask(String baseUrl, String apiKey, String model,
               String recentTranscript, String focus, String trigger) {
        long t0 = System.currentTimeMillis();
        if (apiKey == null || apiKey.trim().isEmpty()) {
            return new Result(false, null, "还没填 API Key", 0);
        }
        try {
            StringBuilder user = new StringBuilder();
            user.append("最近这一段老师说的话（转写稿，有错字，按上下文理解）：\n");
            user.append(recentTranscript).append("\n\n");
            if (trigger != null && !trigger.isEmpty()) {
                user.append("他刚说了这句，像是要点人：\n").append(trigger).append("\n\n");
            }
            if (focus != null && !focus.isEmpty()) {
                user.append("他刚才提过的问题（很可能就是他要问的）：\n").append(focus).append("\n");
            }

            JSONObject payload = new JSONObject();
            payload.put("model", model == null || model.isEmpty() ? "deepseek-chat" : model.trim());
            payload.put("temperature", 0.5);
            // 三行输出用不到 400 token；压到 260 能明显缩短尾字延迟，也少花钱
            payload.put("max_tokens", 260);
            payload.put("stream", false);
            JSONArray messages = new JSONArray();
            messages.put(new JSONObject().put("role", "system").put("content", SYSTEM));
            messages.put(new JSONObject().put("role", "user").put("content", user.toString()));
            payload.put("messages", messages);

            String base = baseUrl == null ? "" : baseUrl.trim();
            if (base.endsWith("/")) base = base.substring(0, base.length() - 1);
            HttpURLConnection conn = (HttpURLConnection) new URL(base + "/chat/completions").openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(20000);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setRequestProperty("Authorization", "Bearer " + apiKey.trim());

            try (OutputStream os = conn.getOutputStream()) {
                os.write(payload.toString().getBytes(StandardCharsets.UTF_8));
            }

            int code = conn.getResponseCode();
            InputStream is = code >= 200 && code < 300 ? conn.getInputStream() : conn.getErrorStream();
            String body = readAll(is);
            long cost = System.currentTimeMillis() - t0;

            if (code < 200 || code >= 300) {
                return new Result(false, null, "HTTP " + code + " · " + truncate(body, 120), cost);
            }

            JSONObject root = new JSONObject(body);
            JSONArray choices = root.optJSONArray("choices");
            if (choices == null || choices.length() == 0) {
                return new Result(false, null, "返回里没有 choices", cost);
            }
            String content = choices.getJSONObject(0).optJSONObject("message")
                    .optString("content", "");
            if (content.trim().isEmpty()) {
                return new Result(false, null, "模型返回空内容", cost);
            }
            return new Result(true, content.trim(), null, cost);

        } catch (Exception e) {
            return new Result(false, null, e.getClass().getSimpleName() + ": " + e.getMessage(),
                    System.currentTimeMillis() - t0);
        }
    }

    private static String readAll(InputStream is) {
        if (is == null) return "";
        StringBuilder sb = new StringBuilder();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) sb.append(line);
        } catch (Exception ignored) {
        }
        return sb.toString();
    }

    private static String truncate(String s, int n) {
        if (s == null) return "";
        return s.length() <= n ? s : s.substring(0, n) + "…";
    }
}
