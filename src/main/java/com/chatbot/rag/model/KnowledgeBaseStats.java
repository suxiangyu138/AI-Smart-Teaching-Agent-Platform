package com.chatbot.rag.model;

/**
 * 知识库统计信息
 *
 * @author suxiangyu
 */
public class KnowledgeBaseStats {

    private int totalDocuments;
    private int totalChunks;
    private long totalChars;
    private int juniorCount;
    private int seniorCount;
    private long diskSizeBytes;
    private String lastIndexedTime;

    public int getTotalDocuments() { return totalDocuments; }
    public void setTotalDocuments(int v) { totalDocuments = v; }

    public int getTotalChunks() { return totalChunks; }
    public void setTotalChunks(int v) { totalChunks = v; }

    public long getTotalChars() { return totalChars; }
    public void setTotalChars(long v) { totalChars = v; }

    public int getJuniorCount() { return juniorCount; }
    public void setJuniorCount(int v) { juniorCount = v; }

    public int getSeniorCount() { return seniorCount; }
    public void setSeniorCount(int v) { seniorCount = v; }

    public long getDiskSizeBytes() { return diskSizeBytes; }
    public void setDiskSizeBytes(long v) { diskSizeBytes = v; }

    public String getLastIndexedTime() { return lastIndexedTime; }
    public void setLastIndexedTime(String v) { lastIndexedTime = v; }
}
