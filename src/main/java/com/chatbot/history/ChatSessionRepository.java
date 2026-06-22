package com.chatbot.history;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ChatSessionRepository extends JpaRepository<ChatSessionEntity, Long> {

    /** 按更新时间倒序，排除已删除 */
    List<ChatSessionEntity> findByDeletedFalseOrderByUpdateTimeDesc(Pageable pageable);

    /** 统计未删除会话数 */
    long countByDeletedFalse();
}
