package com.bistu.speechprobe;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * 课堂触发词检测 + 问题池。
 *
 * <p><b>设计前提</b>（来自真实转写稿的观察）：小爱的转写标点是按停顿硬插的，
 * 一句话常被切成几段，几乎一定丢符号、错同音字。所以这里只做「子串匹配」，
 * 不依赖标点和分词，也不假设一句话是完整的。
 *
 * <p><b>两级触发</b>：
 * <ul>
 *   <li><b>强触发</b>：老师明确在组织提问 / 叫人（「请一个同学」「谁来回答」）。
 *       命中就值得让 AI 先备一份答案。</li>
 *   <li><b>弱触发</b>：老师自问自答式的疑问（「怎么取呢」「什么意思呢」）。
 *       这类 45 分钟能出现几十次，直接生成会淹屏 —— 但它们正是考点，
 *       所以<b>不入屏、只入池</b>，等强触发时当靶子用，课后还能出成问题清单。</li>
 * </ul>
 *
 * <p><b>覆盖不到的</b>：老师直接喊名字（「张三你来说」）。转写本身给不出可用的
 * 信号，只能靠班级名单匹配 —— 但这不要紧：强触发的价值本来就是「提前备稿」，
 * 等真叫到你时答案已经在屏幕上了。
 */
public final class TriggerEngine {

    public enum Level { STRONG, WEAK }

    /** 问题池里的一条。 */
    public static final class Question {
        public final String text;
        public final long atMs;

        Question(String text, long atMs) {
            this.text = text;
            this.atMs = atMs;
        }

        @Override
        public String toString() {
            return fmt(atMs) + "  " + text;
        }
    }

    public static final class Hit {
        public final Level level;
        public final String word;
        /** 命中位置前后的上下文，用来展示「他是从哪句触发的」 */
        public final String context;
        /** 该回答的那个问题；强触发时取自问题池，可能为 null */
        public final String target;

        Hit(Level level, String word, String context, String target) {
            this.level = level;
            this.word = word;
            this.context = context;
            this.target = target;
        }
    }

    /** 强触发：老师明确在组织提问或点名。长词排前面，避免短词抢先命中。 */
    private static final String[] STRONG = {
            "请一个同学", "请一位同学", "请个同学", "我请一位", "我请一个",
            "找个同学", "找一位同学", "找同学来", "叫一位同学", "叫个同学",
            "谁来回答", "谁来答", "谁来说说", "谁来讲讲", "谁愿意说", "谁愿意来",
            "有没有同学", "有没有人愿意", "有同学愿意", "同学来回答",
            "回答一下这个问题", "回答一下", "说一下这个问题", "谈谈你的看法",
            "你来回答", "你来说说", "你先说说", "你试试看", "你来试试",
            "提问一下", "提个问题", "考考大家", "点个名", "点到谁",
            "举手", "举个手",
    };

    /** 弱触发：自问自答式的疑问，只入池。 */
    private static final String[] WEAK = {
            "怎么取呢", "怎么办呢", "什么意思呢", "怎么回事呢", "怎么理解呢",
            "为什么要", "什么是", "怎么样呢", "为什么",
            "大家思考一下", "大家想一想", "你们想一下", "考虑一下",
            "对不对", "是不是", "明白了吗", "清楚了吗",
    };

    /** 同一个强触发在冷却期内不重复生成，避免一句话里好几个词炸出几张卡。 */
    private static final long COOLDOWN_MS = 6000L;
    /** 问题池里多久之前的疑问还算「刚讲过的」，单位毫秒。 */
    private static final long QUESTION_TTL_MS = 150_000L;

    /**
     * 「上一次强触发」的时间戳。
     *
     * <p>初值不能是 0：如果调用方传的是从 0 开始计的时间（比如做回放、跑测试），
     * 那么开头 6 秒会一直落在冷却期里，第一个点名直接被吃掉。用足够远的过去当哨兵。
     */
    private static final long NEVER = -COOLDOWN_MS * 1000;

    private long lastStrongAt = NEVER;
    /**
     * 自适应冷却：老师连着说两句都带触发词时，把冷却拉长，避免一分钟内调好几次模型。
     * 间隔回到正常（&gt;25 秒）就自动恢复。省电，也省得屏幕上刷出一堆重复卡片。
     */
    private long cooldownMs = COOLDOWN_MS;
    private long lastFireAt = NEVER;
    private int strongCount = 0;
    private int weakCount = 0;
    private final List<Question> pool = new ArrayList<>();

    /**
     * 喂一段新识别出来的文字。
     *
     * @param text  这一段转写
     * @param nowMs 当前时间（建议用 SystemClock.elapsedRealtime）
     * @return 强触发返回 STRONG（该生成答案了），弱触发返回 WEAK（已入池），否则 null
     */
    public Hit feed(String text, long nowMs) {
        if (text == null) return null;
        String t = normalize(text);
        if (t.isEmpty()) return null;

        // 先看有没有问题，有就入池（哪怕这句同时含强触发）
        String weakWord = firstContaining(t, WEAK);
        if (weakWord != null) {
            weakCount++;
            pool.add(new Question(trimTo(t, 80), nowMs));
            dropExpired(nowMs);
        }

        String strongWord = firstContaining(t, STRONG);
        if (strongWord != null) {
            if (!allowFire(nowMs)) {
                // 冷却期内不重复生成，但弱触发已经入池了
                return weakWord != null ? new Hit(Level.WEAK, weakWord, contextOf(t, weakWord), null) : null;
            }
            Question q = lastQuestion(nowMs);
            return new Hit(Level.STRONG, strongWord, contextOf(t, strongWord), q == null ? null : q.text);
        }

        if (weakWord != null) {
            return new Hit(Level.WEAK, weakWord, contextOf(t, weakWord), null);
        }
        return null;
    }

    /**
     * 只查强触发，不动问题池。
     *
     * <p>专供「边走边说」这条路径用：系统识别的 partial 结果每 200~500ms 就回来一次，
     * 那时候一句话往往还没说完，拿去入池会存进半截问题。所以 partial 阶段只做
     * 强触发判定（要点名了必须马上知道），问题池等 onResults 拿到定稿再收。
     */
    public Hit detectStrong(String text, long nowMs) {
        if (text == null) return null;
        String t = normalize(text);
        if (t.isEmpty()) return null;
        String w = firstContaining(t, STRONG);
        if (w == null) return null;
        if (!allowFire(nowMs)) return null;
        Question q = lastQuestion(nowMs);
        return new Hit(Level.STRONG, w, contextOf(t, w), q == null ? null : q.text);
    }

    /**
     * 判定这次强触发放不放行，顺带维护自适应冷却。
     *
     * <p>间隔小于 25 秒视为「老师在一句话里反复带触发词」，把冷却乘 1.5 倍；上限 15 秒。
     * 间隔恢复正常就回到 6 秒。目的是别在 30 秒内连调三次模型。
     */
    private boolean allowFire(long nowMs) {
        if (nowMs - lastStrongAt < cooldownMs) return false;

        long gap = lastFireAt == NEVER ? Long.MAX_VALUE : nowMs - lastFireAt;
        if (gap < 25_000L) {
            cooldownMs = Math.min((long) (cooldownMs * 1.5), 15_000L);
        } else {
            cooldownMs = COOLDOWN_MS;
        }
        lastFireAt = nowMs;
        lastStrongAt = nowMs;
        strongCount++;
        return true;
    }

    /** 当前生效的冷却时长，给诊断面板看。 */
    public long currentCooldown() {
        return cooldownMs;
    }

    /** 最近一个还新鲜的问题，优先给 AI 当靶子。 */
    public Question lastQuestion(long nowMs) {
        dropExpired(nowMs);
        return pool.isEmpty() ? null : pool.get(pool.size() - 1);
    }

    /** 整节课攒下来的问题，倒序（最新的在前），用于课后清单。 */
    public List<Question> questionPool(long nowMs) {
        dropExpired(nowMs);
        List<Question> copy = new ArrayList<>(pool);
        Collections.reverse(copy);
        return copy;
    }

    public int strongCount() {
        return strongCount;
    }

    public int weakCount() {
        return weakCount;
    }

    public void reset() {
        lastStrongAt = NEVER;
        lastFireAt = NEVER;
        cooldownMs = COOLDOWN_MS;
        strongCount = 0;
        weakCount = 0;
        pool.clear();
    }

    public String summary() {
        return String.format(Locale.CHINA, "点名 %d 次 · 问题池 %d 条", strongCount, pool.size());
    }

    private void dropExpired(long nowMs) {
        while (!pool.isEmpty() && nowMs - pool.get(0).atMs > QUESTION_TTL_MS) {
            pool.remove(0);
        }
    }

    /** 去掉空白和常见标点，只留字，避免「请 一个 同学」被空格切开漏判。 */
    static String normalize(String s) {
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isWhitespace(c)) continue;
            if ("，。、？！,.?!…“”\";；：:（）()".indexOf(c) >= 0) continue;
            sb.append(c);
        }
        return sb.toString();
    }

    private static String trimTo(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    private static String firstContaining(String text, String[] words) {
        for (String w : words) {
            if (text.contains(w)) return w;
        }
        return null;
    }

    private static String contextOf(String text, String word) {
        int i = text.indexOf(word);
        if (i < 0) return text;
        int from = Math.max(0, i - 12);
        int to = Math.min(text.length(), i + word.length() + 12);
        String head = from > 0 ? "…" : "";
        String tail = to < text.length() ? "…" : "";
        return head + text.substring(from, to) + tail;
    }

    static String fmt(long ms) {
        long s = ms / 1000;
        return String.format(Locale.CHINA, "%02d:%02d", s / 60, s % 60);
    }
}
