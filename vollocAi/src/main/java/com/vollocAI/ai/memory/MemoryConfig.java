package com.vollocAI.ai.memory;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/** 记忆系统配置 */
@Component
@ConfigurationProperties(prefix = "volloc.memory")
public class MemoryConfig {

    /** 短记忆 Redis 最大保留条数 */
    private int workingMemorySize = 20;

    /** 注入 LLM 的最近 N 条短记忆 */
    private int workingMemoryInject = 10;

    /** 触发中间压缩的消息数阈值 */
    private int compressThreshold = 15;

    /** 长记忆向量检索召回数 */
    private int longMemoryTopK = 5;

    /** 长记忆向量相似度阈值 */
    private double longMemoryMinScore = 0.5;

    /** 每隔 N 轮对话触发一次增量摘要 */
    private int roundsPerSummary = 5;

    /** 摘要是否异步生成 */
    private boolean asyncSummarize = true;

    // ── getter / setter ──

    public int getWorkingMemorySize() { return workingMemorySize; }
    public void setWorkingMemorySize(int v) { this.workingMemorySize = v; }

    public int getWorkingMemoryInject() { return workingMemoryInject; }
    public void setWorkingMemoryInject(int v) { this.workingMemoryInject = v; }

    public int getCompressThreshold() { return compressThreshold; }
    public void setCompressThreshold(int v) { this.compressThreshold = v; }

    public int getLongMemoryTopK() { return longMemoryTopK; }
    public void setLongMemoryTopK(int v) { this.longMemoryTopK = v; }

    public double getLongMemoryMinScore() { return longMemoryMinScore; }
    public void setLongMemoryMinScore(double v) { this.longMemoryMinScore = v; }

    public int getRoundsPerSummary() { return roundsPerSummary; }
    public void setRoundsPerSummary(int v) { this.roundsPerSummary = v; }

    public boolean isAsyncSummarize() { return asyncSummarize; }
    public void setAsyncSummarize(boolean v) { this.asyncSummarize = v; }
}
