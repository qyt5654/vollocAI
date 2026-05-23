package com.vollocAI.ai.service;

import jakarta.annotation.Resource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * RAG 文档服务 —— 基于内存向量存储的检索增强生成。
 * 文档通过管理面板上传，或调用 ingest() 接口入库。
 */
@Service
public class DocumentService {

    private static final Logger log = LoggerFactory.getLogger(DocumentService.class);
    private static final int CHUNK_SIZE = 500;
    private static final int OVERLAP = 50;
    private static final int TOP_K = 3;

    @Resource private VectorStore vectorStore;

    /** 文档入库 */
    public int ingest(String docId, String content) {
        List<String> chunks = split(content);
        if (chunks.isEmpty()) return 0;
        return vectorStore.add(docId, chunks);
    }

    /** 向量检索 */
    public List<String> search(String query) {
        return vectorStore.search(query, TOP_K);
    }

    /** Tool 接口：搜索并格式化 */
    public String searchAndFormat(String query) {
        List<String> chunks = search(query);
        if (chunks.isEmpty()) return "知识库中没有找到相关内容";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < chunks.size(); i++)
            sb.append("[").append(i+1).append("] ").append(chunks.get(i)).append("\n");
        return sb.toString();
    }

    /** 文本分片 */
    static List<String> split(String text) {
        List<String> chunks = new ArrayList<>();
        if (text == null || text.isEmpty()) return chunks;
        int start = 0;
        while (start < text.length()) {
            int end = Math.min(start + CHUNK_SIZE, text.length());
            if (end < text.length()) {
                int cut = Math.max(text.lastIndexOf('。', end), text.lastIndexOf('\n', end));
                if (cut > start + CHUNK_SIZE/2) end = cut + 1;
            }
            chunks.add(text.substring(start, end).trim());
            start = end - OVERLAP;
            if (start < 0) start = 0;
        }
        return chunks;
    }
}
