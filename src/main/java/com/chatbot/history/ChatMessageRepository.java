package com.chatbot.history;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ChatMessageRepository extends JpaRepository<ChatMessageEntity, Long> {

    /** 按时间正序获取某会话的全部消息 */
    List<ChatMessageEntity> findBySessionIdOrderByCreateTimeAsc(Long sessionId);

    /** 删除某会话下所有消息 */
    void deleteBySessionId(Long sessionId);
}
