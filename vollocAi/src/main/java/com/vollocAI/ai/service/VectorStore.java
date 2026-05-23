package com.vollocAI.ai.service;

import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingOptions;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 内存向量存储 —— 零外部依赖的 RAG 检索引擎。
 *
 * 用余弦相似度做向量检索，替代 Milvus。
 * 数据量 < 10 万条时性能足够，不需要额外部署。
 */
@Component
public class VectorStore {

    private static final Logger log = LoggerFactory.getLogger(VectorStore.class);
    private final List<Entry> store = new CopyOnWriteArrayList<>();

    @Resource private EmbeddingModel embeddingModel;

    record Entry(String docId, float[] embedding, String content) {}

    /** 入库：向量化 → 写入内存 */
    public int add(String docId, List<String> chunks) {
        List<float[]> embs = embeddingModel.call(
                new EmbeddingRequest(chunks, EmbeddingOptions.EMPTY))
                .getResults().stream().map(r -> r.getOutput()).toList();
        int count = 0;
        for (int i = 0; i < chunks.size(); i++) {
            store.add(new Entry(docId, embs.get(i), chunks.get(i)));
            count++;
        }
        log.info("向量入库 docId={} chunks={}", docId, count);
        return count;
    }

    /** 检索：向量化查询 → 余弦相似度 Top-K */
    public List<String> search(String query, int topK) {
        float[] qEmb = embeddingModel.call(
                new EmbeddingRequest(List.of(query), EmbeddingOptions.EMPTY))
                .getResult().getOutput();

        PriorityQueue<Hit> pq = new PriorityQueue<>(Comparator.comparingDouble(h -> -h.score));
        for (Entry e : store) {
            double sim = cosine(qEmb, e.embedding);
            if (pq.size() < topK) pq.offer(new Hit(e.content, sim));
            else if (sim > pq.peek().score) { pq.poll(); pq.offer(new Hit(e.content, sim)); }
        }
        List<String> result = new ArrayList<>();
        while (!pq.isEmpty()) result.add(pq.poll().content);
        Collections.reverse(result);
        log.info("向量检索 → {}条 (共{}条)", result.size(), store.size());
        return result;
    }

    record Hit(String content, double score) {}

    private double cosine(float[] a, float[] b) {
        double dot = 0, na = 0, nb = 0;
        for (int i = 0; i < a.length; i++) { dot += a[i] * b[i]; na += a[i] * a[i]; nb += b[i] * b[i]; }
        return dot / (Math.sqrt(na) * Math.sqrt(nb));
    }

    public int size() { return store.size(); }
}
