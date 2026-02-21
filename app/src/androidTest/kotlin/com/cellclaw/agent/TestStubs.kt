package com.cellclaw.agent

import com.cellclaw.memory.*

class StubMessageDao : MessageDao {
    override suspend fun insert(message: MessageEntity): Long = 1
    override suspend fun getRecent(conversationId: String, limit: Int): List<MessageEntity> = emptyList()
    override suspend fun getAll(conversationId: String): List<MessageEntity> = emptyList()
    override suspend fun clearConversation(conversationId: String) {}
    override suspend fun count(conversationId: String): Int = 0
}

class StubMemoryFactDao : MemoryFactDao {
    override suspend fun upsert(fact: MemoryFactEntity): Long = 1
    override suspend fun getByCategory(category: String): List<MemoryFactEntity> = emptyList()
    override suspend fun getAll(): List<MemoryFactEntity> = emptyList()
    override suspend fun search(query: String): List<MemoryFactEntity> = emptyList()
    override suspend fun delete(id: Long) {}
}
