package com.cellclaw.memory

import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ConversationStore @Inject constructor(
    private val messageDao: MessageDao
) {
    private var currentConversationId = "default"

    suspend fun addMessage(role: String, content: String) {
        messageDao.insert(
            MessageEntity(
                role = role,
                content = content,
                conversationId = currentConversationId
            )
        )
    }

    suspend fun getRecentMessages(limit: Int = 50): List<MessageEntity> {
        return messageDao.getRecent(currentConversationId, limit).reversed()
    }

    suspend fun getAllMessages(): List<MessageEntity> {
        return messageDao.getAll(currentConversationId)
    }

    suspend fun clearCurrentConversation() {
        messageDao.clearConversation(currentConversationId)
    }

    suspend fun messageCount(): Int {
        return messageDao.count(currentConversationId)
    }

    fun newConversation(id: String) {
        currentConversationId = id
    }

    fun currentId(): String = currentConversationId
}
