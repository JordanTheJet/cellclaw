package com.cellclaw.memory

import androidx.room.*

@Database(
    entities = [MessageEntity::class, MemoryFactEntity::class],
    version = 1,
    exportSchema = false
)
abstract class MemoryDb : RoomDatabase() {
    abstract fun messageDao(): MessageDao
    abstract fun memoryFactDao(): MemoryFactDao
}

@Entity(tableName = "messages")
data class MessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "role") val role: String,
    @ColumnInfo(name = "content") val content: String,
    @ColumnInfo(name = "timestamp") val timestamp: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "conversation_id") val conversationId: String = "default"
)

@Entity(tableName = "memory_facts")
data class MemoryFactEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(name = "key") val key: String,
    @ColumnInfo(name = "value") val value: String,
    @ColumnInfo(name = "category") val category: String = "general",
    @ColumnInfo(name = "confidence") val confidence: Float = 1.0f,
    @ColumnInfo(name = "created_at") val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(name = "updated_at") val updatedAt: Long = System.currentTimeMillis()
)

@Dao
interface MessageDao {
    @Insert
    suspend fun insert(message: MessageEntity): Long

    @Query("SELECT * FROM messages WHERE conversation_id = :conversationId ORDER BY timestamp DESC LIMIT :limit")
    suspend fun getRecent(conversationId: String = "default", limit: Int = 50): List<MessageEntity>

    @Query("SELECT * FROM messages WHERE conversation_id = :conversationId ORDER BY timestamp ASC")
    suspend fun getAll(conversationId: String = "default"): List<MessageEntity>

    @Query("DELETE FROM messages WHERE conversation_id = :conversationId")
    suspend fun clearConversation(conversationId: String = "default")

    @Query("SELECT COUNT(*) FROM messages WHERE conversation_id = :conversationId")
    suspend fun count(conversationId: String = "default"): Int
}

@Dao
interface MemoryFactDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(fact: MemoryFactEntity): Long

    @Query("SELECT * FROM memory_facts WHERE category = :category ORDER BY updated_at DESC")
    suspend fun getByCategory(category: String): List<MemoryFactEntity>

    @Query("SELECT * FROM memory_facts ORDER BY updated_at DESC")
    suspend fun getAll(): List<MemoryFactEntity>

    @Query("SELECT * FROM memory_facts WHERE `key` LIKE '%' || :query || '%' OR `value` LIKE '%' || :query || '%'")
    suspend fun search(query: String): List<MemoryFactEntity>

    @Query("DELETE FROM memory_facts WHERE id = :id")
    suspend fun delete(id: Long)
}
