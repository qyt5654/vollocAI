package com.vollocAI.ai.rag;

import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingOptions;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

/** 内存向量存储 —— 余弦相似度检索引擎 */
@Component
public class VectorStore {

    private static final Logger log = LoggerFactory.getLogger(VectorStore.class);
    private final List<Entry> store = new CopyOnWriteArrayList<>();

    @Resource private EmbeddingModel embeddingModel;

    record Entry(String docId, float[] embedding, String content) {}

    public List<float[]> embed(List<String> texts) {
        return embeddingModel.call(new EmbeddingRequest(texts, EmbeddingOptions.EMPTY))
                .getResults().stream().map(r -> r.getOutput()).toList();
    }

    public int add(String docId, List<String> chunks) {
        List<float[]> embs = embed(chunks);
        for (int i = 0; i < chunks.size(); i++)
            store.add(new Entry(docId, embs.get(i), chunks.get(i)));
        log.info("向量入库 docId={} chunks={}", docId, chunks.size());
        return chunks.size();
    }

    static final double MIN_SCORE = 0.75;

    public List<String> search(String query, int topK) {
        return search(query, topK, MIN_SCORE);
    }

    public List<String> search(String query, int topK, double minScore) {
        float[] q = embed(List.of(query)).get(0);
        PriorityQueue<Hit> pq = new PriorityQueue<>(Comparator.comparingDouble(h -> -h.score));
        for (Entry e : store) {
            double sim = cosine(q, e.embedding);
            if (pq.size() < topK) pq.offer(new Hit(e.content, sim));
            else if (sim > pq.peek().score) { pq.poll(); pq.offer(new Hit(e.content, sim)); }
        }
        List<String> result = new ArrayList<>();
        while (!pq.isEmpty()) {
            Hit h = pq.poll();
            if (h.score >= minScore) result.add(h.content);
        }
        Collections.reverse(result);
        return result;
    }

    record Hit(String content, double score) {}

    private double cosine(float[] a, float[] b) {
        double dot = 0, na = 0, nb = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            na += a[i] * a[i];
            nb += b[i] * b[i];
        }
        return dot / (Math.sqrt(na) * Math.sqrt(nb));
    }

    public int size() { return store.size(); }
}
