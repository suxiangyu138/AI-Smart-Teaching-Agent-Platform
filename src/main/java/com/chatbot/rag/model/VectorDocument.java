package com.chatbot.rag.model;

/**
 * 向量化文档 — 文档切片 + 嵌入向量
 *
 * @author suxiangyu
 */
public class VectorDocument {

    private String id;
    private String sourceFile;
    private String content;
    private float[] embedding;
    private String grade;
    private String chapterTitle;
    private String knowledgePoint;
    private String questionType;

    public VectorDocument() {}

    public String getId() { return id; }
    public void setId(String v) { id = v; }

    public String getSourceFile() { return sourceFile; }
    public void setSourceFile(String v) { sourceFile = v; }

    public String getContent() { return content; }
    public void setContent(String v) { content = v; }

    public float[] getEmbedding() { return embedding; }
    public void setEmbedding(float[] v) { embedding = v; }

    public String getGrade() { return grade; }
    public void setGrade(String v) { grade = v; }

    public String getChapterTitle() { return chapterTitle; }
    public void setChapterTitle(String v) { chapterTitle = v; }

    public String getKnowledgePoint() { return knowledgePoint; }
    public void setKnowledgePoint(String v) { knowledgePoint = v; }

    public String getQuestionType() { return questionType; }
    public void setQuestionType(String v) { questionType = v; }
}
