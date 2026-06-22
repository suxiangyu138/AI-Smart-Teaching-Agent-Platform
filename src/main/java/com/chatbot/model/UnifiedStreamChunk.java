package com.chatbot.model;

/**
 * 后端 → 前端 统一SSE流式数据
 *
 * @author suxiangyu
 */
public class UnifiedStreamChunk {
    public static final String TYPE_CHUNK = "chunk";
    public static final String TYPE_REASONING = "reasoning";
    public static final String TYPE_FINISH = "finish";
    public static final String TYPE_ERROR = "error";

    /** chunk=文本片段 / reasoning=思考过程 / finish=结束 / error=异常 */
    private String type;
    /** 本次流式返回文本片段 */
    private String content;
    /** 思考/推理过程文本 */
    private String reasoning;
    /** 当前累计完整回答文本 */
    private String fullContent;
    /** 当前累计完整思考文本 */
    private String fullReasoning;
    /** type=error 时的报错信息 */
    private String errMsg;

    public UnifiedStreamChunk() {}

    public UnifiedStreamChunk(String type, String content,
                              String fullContent) {
        this.type = type;
        this.content = content;
        this.fullContent = fullContent;
    }

    public static UnifiedStreamChunk chunk(String content, String full) {
        return new UnifiedStreamChunk(TYPE_CHUNK, content, full);
    }

    public static UnifiedStreamChunk reasoning(String reasoning, String fullReasoning) {
        UnifiedStreamChunk c = new UnifiedStreamChunk();
        c.type = TYPE_REASONING;
        c.reasoning = reasoning;
        c.fullReasoning = fullReasoning;
        return c;
    }

    public static UnifiedStreamChunk done(String full) {
        return new UnifiedStreamChunk("done", "", full);
    }

    public static UnifiedStreamChunk finish(String full, String fullReasoning) {
        UnifiedStreamChunk c = new UnifiedStreamChunk();
        c.type = TYPE_FINISH;
        c.fullContent = full;
        c.fullReasoning = fullReasoning;
        return c;
    }

    public static UnifiedStreamChunk error(String msg) {
        UnifiedStreamChunk c = new UnifiedStreamChunk();
        c.type = TYPE_ERROR;
        c.errMsg = msg;
        return c;
    }

    public String getType() { return type; }
    public void setType(String v) { type = v; }
    public String getContent() { return content; }
    public void setContent(String v) { content = v; }
    public String getReasoning() { return reasoning; }
    public void setReasoning(String v) { reasoning = v; }
    public String getFullContent() { return fullContent; }
    public void setFullContent(String v) { fullContent = v; }
    public String getFullReasoning() { return fullReasoning; }
    public void setFullReasoning(String v) { fullReasoning = v; }
    public String getErrMsg() { return errMsg; }
    public void setErrMsg(String v) { errMsg = v; }
}
