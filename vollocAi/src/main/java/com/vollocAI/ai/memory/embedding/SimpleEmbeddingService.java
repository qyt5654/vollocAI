package com.vollocAI.ai.memory.embedding;

import com.vollocAI.ai.model.DatabaseAi;
import com.vollocAI.ai.model.DatabaseAiService;
import com.vollocAI.ai.model.dao.ModelAssignmentDao;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.openai.OpenAiEmbeddingModel;
import org.springframework.ai.openai.OpenAiEmbeddingOptions;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * 自建 Embedding 服务 —— 解决 Spring AI 自动配置被排除导致 EmbeddingModel 不可用的问题。
 *
 * <p>采用与 {@code AiUtils.model()} 相同的手动构建模式，直接 new OpenAiEmbeddingModel，
 * 配合 LRU 缓存减少重复 API 调用。</p>
 */
@Service
public class SimpleEmbeddingService {

    private static final Logger log = LoggerFactory.getLogger(SimpleEmbeddingService.class);
    private static final int CACHE_MAX = 256;

    @Resource private DatabaseAiService databaseAiService;
    @Resource private ModelAssignmentDao modelAssignmentDao;

    private volatile OpenAiEmbeddingModel embeddingModel;
    private volatile boolean available;

    /** LRU 嵌入缓存：key = 文本，value = 向量 */
    private final LinkedHashMap<String, float[]> cache = new LinkedHashMap<>(64, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, float[]> eldest) {
            return size() > CACHE_MAX;
        }
    };
    private final ReentrantReadWriteLock cacheLock = new ReentrantReadWriteLock();

    @PostConstruct
    void init() {
        try {
            DatabaseAi cfg = resolveModel();
            if (cfg == null || cfg.getAiApiKey() == null || cfg.getAiApiUrl() == null) {
                log.warn("[Embedding] 未找到可用模型配置，向量化不可用");
                available = false;
                return;
            }
            OpenAiApi api = new OpenAiApi(cfg.getAiApiUrl(), cfg.getAiApiKey());
            this.embeddingModel = new OpenAiEmbeddingModel(api);
            this.available = true;
            log.info("[Embedding] 初始化完成 baseUrl={}", cfg.getAiApiUrl());
        } catch (Exception e) {
            log.error("[Embedding] 初始化失败", e);
            this.available = false;
        }
    }

    public boolean isAvailable() { return available && embeddingModel != null; }

    /** 对单文本做向量化，带缓存 */
    public float[] embed(String text) {
        if (text == null || text.isEmpty()) return new float[0];
        if (!isAvailable()) {
            log.warn("[Embedding] 不可用，返回空向量");
            return new float[0];
        }

        // 读缓存
        cacheLock.readLock().lock();
        try {
            float[] cached = cache.get(text);
            if (cached != null) return cached;
        } finally { cacheLock.readLock().unlock(); }

        // 调用 API
        try {
            float[] vec = embeddingModel.embed(text);
            if (vec == null) return new float[0];

            cacheLock.writeLock().lock();
            try { cache.put(text, vec); } finally { cacheLock.writeLock().unlock(); }
            return vec;
        } catch (Exception e) {
            log.error("[Embedding] 调用失败 text={}", text.length() > 50 ? text.substring(0, 50) + "..." : text, e);
            return new float[0];
        }
    }

    /** 批量向量化（逐个调，利用缓存） */
    public List<float[]> embedBatch(List<String> texts) {
        if (texts == null || texts.isEmpty()) return List.of();
        List<float[]> result = new ArrayList<>(texts.size());
        for (String t : texts) result.add(embed(t));
        return result;
    }

    /** 将 {@code float[]} 序列化为 JSON 数组字符串，供 MySQL JSON 列存储 */
    public static String toJson(float[] vec) {
        if (vec == null || vec.length == 0) return "[]";
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < vec.length; i++) {
            if (i > 0) sb.append(",");
            sb.append(String.format(Locale.US, "%.6f", vec[i]));
        }
        sb.append("]");
        return sb.toString();
    }

    /** 从 JSON 数组字符串反序列化 {@code float[]} */
    public static float[] fromJson(String json) {
        if (json == null || json.isEmpty() || "[]".equals(json)) return new float[0];
        String[] parts = json.replace("[", "").replace("]", "").split(",");
        float[] vec = new float[parts.length];
        for (int i = 0; i < parts.length; i++) {
            try { vec[i] = Float.parseFloat(parts[i].trim()); }
            catch (NumberFormatException e) { vec[i] = 0f; }
        }
        return vec;
    }

    /** 余弦相似度 */
    public static double cosine(float[] a, float[] b) {
        if (a == null || b == null || a.length == 0 || b.length == 0 || a.length != b.length) return 0;
        double dot = 0, na = 0, nb = 0;
        for (int i = 0; i < a.length; i++) {
            dot += (double) a[i] * b[i];
            na += (double) a[i] * a[i];
            nb += (double) b[i] * b[i];
        }
        double denom = Math.sqrt(na) * Math.sqrt(nb);
        return denom == 0 ? 0 : dot / denom;
    }

    // ── private ──

    private DatabaseAi resolveModel() {
        // selectByDatabaseAi(空对象) 会返回全量表记录（XML 中无必填字段过滤）
        List<DatabaseAi> all = databaseAiService.selectByDatabaseAi(new DatabaseAi());
        if (all != null && !all.isEmpty()) return all.get(0);
        return null;
    }
}
