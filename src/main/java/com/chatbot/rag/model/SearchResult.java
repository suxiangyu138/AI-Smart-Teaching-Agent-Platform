package com.chatbot.rag.model;

/**
 * 向量检索结果
 *
 * @author suxiangyu
 */
public class SearchResult implements Comparable<SearchResult> {

    /** 匹配的文档切片 */
    private DocumentChunk chunk;
    /** 余弦相似度得分 [0, 1] */
    private double score;
    /** 来源文件名 */
    private String sourceFile;
    /** 匹配内容摘要（前150字符） */
    private String snippet;

    public SearchResult() {}

    public DocumentChunk getChunk() { return chunk; }
    public void setChunk(DocumentChunk v) { chunk = v; }

    public double getScore() { return score; }
    public void setScore(double v) { score = v; }

    public String getSourceFile() { return sourceFile; }
    public void setSourceFile(String v) { sourceFile = v; }

    public String getSnippet() { return snippet; }
    public void setSnippet(String v) { snippet = v; }

    @Override
    public int compareTo(SearchResult o) {
        return Double.compare(o.score, this.score);
    }
}
