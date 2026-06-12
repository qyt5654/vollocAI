package com.vollocAI.ai.memory;

import com.alibaba.fastjson.JSON;
import com.vollocAI.ai.memory.dao.MemoryFactDao;
import com.vollocAI.ai.memory.dao.MemorySummaryDao;
import com.vollocAI.ai.memory.embedding.SimpleEmbeddingService;
import com.vollocAI.ai.memory.model.MemoryFact;
import com.vollocAI.ai.memory.model.MemorySummary;
import io.milvus.client.MilvusServiceClient;
import io.milvus.grpc.DataType;
import io.milvus.grpc.FieldData;
import io.milvus.param.IndexType;
import io.milvus.param.MetricType;
import io.milvus.param.collection.*;
import io.milvus.param.dml.InsertParam;
import io.milvus.param.dml.SearchParam;
import io.milvus.param.index.CreateIndexParam;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 长记忆层 —— 以 sessionId 为最小粒度，DB + Milvus 持久化。
 *
 * <h3>动态阈值</h3>
 * 不定死 minScore，基于本次检索的分数分布自适应计算。
 */
@Component
public class LongMemoryStore {

    private static final Logger log = LoggerFactory.getLogger(LongMemoryStore.class);
    private static final String MILVUS_COL = "long_term_memory";

    @Resource private MemorySummaryDao summaryDao;
    @Resource private MemoryFactDao factDao;
    @Resource private SimpleEmbeddingService embeddingService;
    @Resource private MemoryConfig config;

    @Autowired(required = false) private MilvusServiceClient milvus;

    @PostConstruct
    void ensureCollection() {
        if (milvus == null) return;
        if (milvus.hasCollection(HasCollectionParam.newBuilder().withCollectionName(MILVUS_COL).build()).getData())
            return;
        milvus.createCollection(CreateCollectionParam.newBuilder().withCollectionName(MILVUS_COL)
                .withFieldTypes(Arrays.asList(
                        FieldType.newBuilder().withName("id").withDataType(DataType.Int64)
                                .withPrimaryKey(true).withAutoID(true).build(),
                        FieldType.newBuilder().withName("session_id").withDataType(DataType.VarChar)
                                .withMaxLength(64).build(),
                        FieldType.newBuilder().withName("fact_id").withDataType(DataType.VarChar)
                                .withMaxLength(64).build(),
                        FieldType.newBuilder().withName("content").withDataType(DataType.VarChar)
                                .withMaxLength(4096).build(),
                        FieldType.newBuilder().withName("embedding").withDataType(DataType.FloatVector)
                                .withDimension(1536).build()))
                .build());
        milvus.createIndex(CreateIndexParam.newBuilder().withCollectionName(MILVUS_COL)
                .withFieldName("embedding").withIndexType(IndexType.IVF_FLAT)
                .withMetricType(MetricType.IP).withExtraParam("{\"nlist\":128}").build());
        milvus.loadCollection(LoadCollectionParam.newBuilder().withCollectionName(MILVUS_COL).build());
        log.info("Milvus collection [{}] 已创建", MILVUS_COL);
    }

    // ═══════════════════ 摘要 ═══════════════════

    public void saveSummary(String sessionId, String summary, List<String> keyPoints, int tokenCount) {
        MemorySummary s = new MemorySummary();
        s.setSessionId(sessionId);
        s.setSummary(summary);
        s.setKeyPoints(JSON.toJSONString(keyPoints));
        s.setTokenCount(tokenCount);
        MemorySummary exist = summaryDao.findBySessionId(sessionId);
        if (exist != null) {
            s.setId(exist.getId());
            summaryDao.update(s);
        } else {
            summaryDao.insert(s);
        }
        log.info("[LongMemory] 摘要已保存 sessionId={} keyPoints={}", sessionId, keyPoints.size());
    }

    public MemorySummary getSessionSummary(String sessionId) {
        if (sessionId == null) return null;
        return summaryDao.findBySessionId(sessionId);
    }

    // ═══════════════════ 事实 ═══════════════════

    public void saveFacts(List<MemoryFact> facts) {
        if (facts == null || facts.isEmpty()) return;
        for (MemoryFact f : facts) {
            if (embeddingService.isAvailable()) {
                f.setEmbedding(SimpleEmbeddingService.toJson(embeddingService.embed(f.getFactContent())));
            }
            factDao.insert(f);
            if (milvus != null && embeddingService.isAvailable()) {
                try { milvusInsert(f); } catch (Exception e) { log.warn("[LongMemory] Milvus 写入失败", e); }
            }
        }
        log.info("[LongMemory] 保存了 {} 条事实", facts.size());
    }

    /**
     * 检索会话相关的记忆事实。
     *
     * <p>Milvus 可用时走向量检索（Collection: long_term_memory），不可用时降级 DB 关键词。</p>
     */
    public List<ScoredFact> searchFacts(String sessionId, String query) {
        int topK = config.getLongMemoryTopK();

        // PREFERENCE 型事实独立检索 —— 不依赖向量，直接从 DB 加载（量少价值高）
        List<ScoredFact> preferences = factDao.findPreferencesBySession(sessionId)
                .stream()
                .map(f -> new ScoredFact(f, 0.9, "preference"))
                .toList();

        // FACT + EVENT → 向量检索 / 关键词兜底
        List<ScoredFact> all;
        if (milvus != null && embeddingService.isAvailable()) {
            all = milvusSearch(sessionId, query, topK * 2);
        } else {
            all = keywordSearch(sessionId, query, topK * 2);
        }

        // PREFERENCE 排最前面合并
        all.addAll(0, preferences);

        // 去重 + 排序
        Map<Long, ScoredFact> merged = new LinkedHashMap<>();
        for (ScoredFact sf : all) {
            ScoredFact exist = merged.get(sf.fact.getId());
            if (exist == null || sf.score > exist.score) merged.put(sf.fact.getId(), sf);
        }

        List<ScoredFact> sorted = new ArrayList<>(merged.values());
        sorted.sort((a, b) -> Double.compare(b.score, a.score));

        double threshold = computeDynamicThreshold(sorted);
        List<ScoredFact> filtered = sorted.stream()
                .filter(sf -> sf.score >= threshold)
                .limit(topK)
                .collect(Collectors.toList());

        log.info("[LongMemory] 检索完成 sessionId={} 候选={} 阈值={} 命中={}",
                sessionId, sorted.size(), String.format("%.3f", threshold), filtered.size());
        return filtered;
    }

    // ═══════════════════ Milvus 向量检索 ═══════════════════

    private List<ScoredFact> milvusSearch(String sessionId, String query, int topK) {
        try {
            float[] queryVec = embeddingService.embed(query);
            if (queryVec.length == 0) return keywordSearch(sessionId, query, topK);

            io.milvus.grpc.SearchResults data = milvus.search(
                    SearchParam.newBuilder()
                            .withCollectionName(MILVUS_COL)
                            .withVectorFieldName("embedding")
                            .withVectors(List.of(toFloats(queryVec)))
                            .withTopK(topK)
                            .withMetricType(MetricType.IP)
                            .withParams("{\"nprobe\":16}")
                            .withOutFields(List.of("fact_id", "session_id", "content"))
                            .withExpr("session_id == \"" + sessionId + "\"")
                            .build()
            ).getData();

            List<ScoredFact> results = new ArrayList<>();
            for (int i = 0; i < data.getResults().getFieldsDataCount(); i++) {
                float score = data.getResults().getScores(i);
                // 回查 MySQL 获取完整 MemoryFact
                String factId = extractField(data.getResults(), i, "fact_id");
                if (factId == null || factId.isEmpty()) continue;
                try {
                    MemoryFact f = factDao.findById(Long.parseLong(factId));
                    if (f != null) results.add(new ScoredFact(f, score, "milvus"));
                } catch (NumberFormatException ignore) {}
            }
            return results;
        } catch (Exception e) {
            log.warn("[LongMemory] Milvus 检索异常，降级到 DB", e);
            return keywordSearch(sessionId, query, topK);
        }
    }

    private static String extractField(io.milvus.grpc.SearchResultData sr, int idx, String fieldName) {
        for (FieldData fd : sr.getFieldsDataList()) {
            if (fieldName.equals(fd.getFieldName()) && fd.hasScalars() && fd.getScalars().hasStringData()) {
                var data = fd.getScalars().getStringData();
                if (idx < data.getDataCount()) return data.getData(idx);
            }
        }
        return null;
    }

    // ═══════════════════ 关键词兜底 ═══════════════════

    private List<ScoredFact> keywordSearch(String sessionId, String query, int topK) {
        String[] words = query.split("[\\s,，。！？.!?]+");
        List<ScoredFact> all = new ArrayList<>();
        Set<Long> seen = new HashSet<>();

        for (int i = words.length - 1; i >= 0 && all.size() < topK * 2; i--) {
            String w = words[i].trim();
            if (w.length() < 2) continue;
            for (MemoryFact f : factDao.searchBySession(sessionId, w, topK)) {
                if (seen.add(f.getId())) {
                    double score = keywordScore(w, f.getFactContent()) * f.getConfidence();
                    if (MemoryFact.TYPE_EVENT.equals(f.getFactType())) {
                        score *= (1.0 - f.getDecayFactor());
                    }
                    all.add(new ScoredFact(f, score, "keyword:" + w));
                }
            }
        }
        all.sort((a, b) -> Double.compare(b.score, a.score));
        return all.subList(0, Math.min(topK, all.size()));
    }

    private static double keywordScore(String keyword, String content) {
        if (content == null || keyword == null) return 0.5;
        int idx = content.toLowerCase().indexOf(keyword.toLowerCase());
        if (idx < 0) return 0.5;
        return 0.5 + 0.5 * (1.0 - (double) idx / Math.max(content.length(), 1));
    }

    // ═══════════════════ 动态阈值 ═══════════════════

    double computeDynamicThreshold(List<ScoredFact> candidates) {
        if (candidates.isEmpty()) return 0.0;
        if (candidates.size() == 1) return candidates.get(0).score * 0.6;

        int topN = Math.min(3, candidates.size());
        double topAvg = candidates.stream().limit(topN).mapToDouble(s -> s.score).average().orElse(0.5);
        double mean = candidates.stream().mapToDouble(s -> s.score).average().orElse(0.5);
        double variance = candidates.stream().mapToDouble(s -> Math.pow(s.score - mean, 2)).average().orElse(0.0);
        double stdDev = Math.sqrt(variance);

        double threshold = Math.max(topAvg * 0.5, mean + 0.3 * stdDev);
        threshold = Math.max(threshold, config.getLongMemoryMinScore());
        if (threshold > 0.95) threshold = 0.8;
        return threshold;
    }

    // ═══════════════════ Milvus ═══════════════════

    private void milvusInsert(MemoryFact f) {
        if (milvus == null) return;
        float[] vec = SimpleEmbeddingService.fromJson(f.getEmbedding());
        if (vec.length == 0) return;

        milvus.insert(InsertParam.newBuilder()
                .withCollectionName(MILVUS_COL)
                .withFields(Arrays.asList(
                        new InsertParam.Field("session_id", List.of(f.getSessionId())),
                        new InsertParam.Field("fact_id", List.of(String.valueOf(f.getId()))),
                        new InsertParam.Field("content", List.of(truncate(f.getFactContent(), 4096))),
                        new InsertParam.Field("embedding", List.of(toFloats(vec))))
                )
                .build());
    }

    private static String truncate(String s, int max) {
        return s.length() > max ? s.substring(0, max) : s;
    }

    private static List<Float> toFloats(float[] arr) {
        List<Float> l = new ArrayList<>(arr.length);
        for (float v : arr) l.add(v);
        return l;
    }

    /** 带分数的记忆检索结果 */
    public record ScoredFact(MemoryFact fact, double score, String source) {}
}
