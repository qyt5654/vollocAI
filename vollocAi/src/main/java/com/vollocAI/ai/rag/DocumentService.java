package com.vollocAI.ai.rag;

import io.milvus.client.MilvusServiceClient;
import io.milvus.grpc.FieldData;
import io.milvus.param.MetricType;
import io.milvus.param.dml.InsertParam;
import io.milvus.param.dml.SearchParam;
import jakarta.annotation.Resource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import com.vollocAI.ai.rag.VectorStore;

import java.util.*;

/** RAG 文档服务 —— Milvus 优先，内存 VectorStore 兜底 */
@Service
public class DocumentService {

    private static final String COL = "knowledge_base";
    private static final int TOP_K = 3;

    @Autowired(required = false) private MilvusServiceClient milvus;
    @Resource private VectorStore vectorStore;

    public int ingest(String docId, String content) {
        List<String> chunks = split(content);
        if (chunks.isEmpty()) return 0;
        if (milvus != null) return milvusIngest(docId, chunks);
        return vectorStore.add(docId, chunks);
    }

    public List<String> search(String query) {
        if (milvus != null) return milvusSearch(query);
        if (vectorStore.size() == 0) return List.of(FALLBACK);
        return vectorStore.search(query, TOP_K);
    }

    public String searchAndFormat(String query) {
        List<String> chunks = new ArrayList<>(search(query));
        if (chunks.isEmpty()) chunks.add(FALLBACK);
        var sb = new StringBuilder();
        for (int i = 0; i < chunks.size(); i++)
            sb.append("[").append(i + 1).append("] ").append(chunks.get(i)).append("\n");
        return sb.toString();
    }

    // ── Milvus ──────────────────────────────

    private int milvusIngest(String docId, List<String> chunks) {
        List<float[]> embs = vectorStore.embed(chunks);
        List<String> docIds = new ArrayList<>(), contents = new ArrayList<>();
        List<List<Float>> vectors = new ArrayList<>();
        for (int i = 0; i < chunks.size(); i++) {
            docIds.add(docId); contents.add(chunks.get(i));
            vectors.add(toFloats(embs.get(i)));
        }
        milvus.insert(InsertParam.newBuilder().withCollectionName(COL)
                .withFields(Arrays.asList(
                        new InsertParam.Field("doc_id", docIds),
                        new InsertParam.Field("content", contents),
                        new InsertParam.Field("embedding", vectors)))
                .build());
        milvus.flush(io.milvus.param.collection.FlushParam.newBuilder()
                .withCollectionNames(List.of(COL)).build());
        return chunks.size();
    }

    private List<String> milvusSearch(String query) {
        float[] q = vectorStore.embed(List.of(query)).get(0);
        var r = milvus.search(SearchParam.newBuilder().withCollectionName(COL)
                .withVectorFieldName("embedding").withVectors(List.of(toFloats(q)))
                .withTopK(TOP_K).withMetricType(MetricType.IP).withParams("{\"nprobe\":16}")
                .withOutFields(List.of("content")).build()).getData();
        List<String> result = new ArrayList<>();
        for (FieldData fd : r.getResults().getFieldsDataList())
            if (fd.hasScalars() && fd.getScalars().hasStringData())
                result.add(fd.getScalars().getStringData().getData(0));
        return result;
    }

    // ── 分片 / 工具 ─────────────────────────

    static List<String> split(String text) {
        List<String> chunks = new ArrayList<>();
        if (text == null || text.isEmpty()) return chunks;
        int len = text.length();
        if (len <= 500) { chunks.add(text); return chunks; }
        int start = 0;
        while (start < len) {
            int end = Math.min(start + 500, len);
            if (end < len) {
                int cut = Math.max(text.lastIndexOf('。', end), text.lastIndexOf('\n', end));
                if (cut > start + 250) end = cut + 1;
            }
            chunks.add(text.substring(start, end).trim());
            start = Math.max(start + 1, end - 50);
        }
        return chunks;
    }

    private static List<Float> toFloats(float[] arr) {
        List<Float> l = new ArrayList<>(arr.length);
        for (float v : arr) l.add(v);
        return l;
    }

    private static final String FALLBACK = "知识库为空，尚未收录相关文档。可通过管理面板上传。";
}
