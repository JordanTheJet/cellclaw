package com.cellclaw.memory

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MemoryDbInstrumentedTest {

    private lateinit var db: MemoryDb
    private lateinit var messageDao: MessageDao
    private lateinit var factDao: MemoryFactDao

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, MemoryDb::class.java)
            .allowMainThreadQueries()
            .build()
        messageDao = db.messageDao()
        factDao = db.memoryFactDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    // --- Message DAO tests ---

    @Test
    fun insertAndRetrieveMessage() = runTest {
        val msg = MessageEntity(role = "user", content = "Hello")
        messageDao.insert(msg)

        val messages = messageDao.getAll("default")
        assertEquals(1, messages.size)
        assertEquals("user", messages[0].role)
        assertEquals("Hello", messages[0].content)
    }

    @Test
    fun getRecentReturnsLimited() = runTest {
        repeat(20) {
            messageDao.insert(MessageEntity(role = "user", content = "Message $it"))
        }

        val recent = messageDao.getRecent("default", 5)
        assertEquals(5, recent.size)
    }

    @Test
    fun clearConversation() = runTest {
        messageDao.insert(MessageEntity(role = "user", content = "Test"))
        messageDao.insert(MessageEntity(role = "assistant", content = "Reply"))
        assertEquals(2, messageDao.count("default"))

        messageDao.clearConversation("default")
        assertEquals(0, messageDao.count("default"))
    }

    @Test
    fun separateConversations() = runTest {
        messageDao.insert(MessageEntity(role = "user", content = "A", conversationId = "conv1"))
        messageDao.insert(MessageEntity(role = "user", content = "B", conversationId = "conv2"))
        messageDao.insert(MessageEntity(role = "user", content = "C", conversationId = "conv1"))

        assertEquals(2, messageDao.count("conv1"))
        assertEquals(1, messageDao.count("conv2"))
    }

    @Test
    fun clearOnlyTargetConversation() = runTest {
        messageDao.insert(MessageEntity(role = "user", content = "A", conversationId = "conv1"))
        messageDao.insert(MessageEntity(role = "user", content = "B", conversationId = "conv2"))

        messageDao.clearConversation("conv1")

        assertEquals(0, messageDao.count("conv1"))
        assertEquals(1, messageDao.count("conv2"))
    }

    // --- Fact DAO tests ---

    @Test
    fun insertAndRetrieveFact() = runTest {
        factDao.upsert(MemoryFactEntity(key = "user_name", value = "Jordan", category = "identity"))

        val facts = factDao.getAll()
        assertEquals(1, facts.size)
        assertEquals("user_name", facts[0].key)
        assertEquals("Jordan", facts[0].value)
        assertEquals("identity", facts[0].category)
    }

    @Test
    fun getByCategory() = runTest {
        factDao.upsert(MemoryFactEntity(key = "name", value = "Jordan", category = "identity"))
        factDao.upsert(MemoryFactEntity(key = "color", value = "blue", category = "preferences"))
        factDao.upsert(MemoryFactEntity(key = "age", value = "25", category = "identity"))

        val identity = factDao.getByCategory("identity")
        assertEquals(2, identity.size)

        val prefs = factDao.getByCategory("preferences")
        assertEquals(1, prefs.size)
    }

    @Test
    fun searchFacts() = runTest {
        factDao.upsert(MemoryFactEntity(key = "favorite_food", value = "pizza", category = "preferences"))
        factDao.upsert(MemoryFactEntity(key = "name", value = "Jordan", category = "identity"))

        val results = factDao.search("pizza")
        assertEquals(1, results.size)
        assertEquals("favorite_food", results[0].key)

        val nameResults = factDao.search("Jordan")
        assertEquals(1, nameResults.size)
    }

    @Test
    fun deleteFact() = runTest {
        val id = factDao.upsert(MemoryFactEntity(key = "temp", value = "val"))
        assertEquals(1, factDao.getAll().size)

        factDao.delete(id)
        assertEquals(0, factDao.getAll().size)
    }

    @Test
    fun upsertReplacesFact() = runTest {
        val id = factDao.upsert(MemoryFactEntity(key = "k", value = "v1"))
        factDao.upsert(MemoryFactEntity(id = id, key = "k", value = "v2"))

        val facts = factDao.getAll()
        assertEquals(1, facts.size)
        assertEquals("v2", facts[0].value)
    }

    // --- ConversationStore tests ---

    @Test
    fun conversationStoreAddAndRetrieve() = runTest {
        val store = ConversationStore(messageDao)
        store.addMessage("user", "Hello")
        store.addMessage("assistant", "Hi there")

        val messages = store.getRecentMessages(10)
        assertEquals(2, messages.size)
        assertEquals("Hello", messages[0].content)
        assertEquals("Hi there", messages[1].content)
    }

    @Test
    fun conversationStoreNewConversation() = runTest {
        val store = ConversationStore(messageDao)
        assertEquals("default", store.currentId())

        store.newConversation("conv_123")
        assertEquals("conv_123", store.currentId())

        store.addMessage("user", "In new conversation")
        val msgs = store.getRecentMessages()
        assertEquals(1, msgs.size)
    }

    @Test
    fun conversationStoreClear() = runTest {
        val store = ConversationStore(messageDao)
        store.addMessage("user", "msg1")
        store.addMessage("user", "msg2")
        assertEquals(2, store.messageCount())

        store.clearCurrentConversation()
        assertEquals(0, store.messageCount())
    }

    // --- SemanticMemory tests ---

    @Test
    fun semanticMemoryRememberAndRecall() = runTest {
        val memory = SemanticMemory(factDao)
        memory.remember("name", "Jordan", "identity")
        memory.remember("color", "blue", "preferences")

        val identity = memory.recall("identity")
        assertEquals(1, identity.size)
        assertEquals("Jordan", identity[0].value)

        val all = memory.recallAll()
        assertEquals(2, all.size)
    }

    @Test
    fun semanticMemorySearch() = runTest {
        val memory = SemanticMemory(factDao)
        memory.remember("city", "San Francisco", "location")
        memory.remember("food", "sushi", "preferences")

        val results = memory.search("Francisco")
        assertEquals(1, results.size)
        assertEquals("city", results[0].key)
    }

    @Test
    fun semanticMemoryForget() = runTest {
        val memory = SemanticMemory(factDao)
        memory.remember("temp", "data")
        val facts = memory.recallAll()
        assertEquals(1, facts.size)

        memory.forget(facts[0].id)
        assertEquals(0, memory.recallAll().size)
    }

    @Test
    fun semanticMemoryBuildContext() = runTest {
        val memory = SemanticMemory(factDao)
        memory.remember("name", "Jordan", "identity")
        memory.remember("food", "pizza", "preferences")

        val context = memory.buildContext()
        assertTrue(context.contains("Known Facts"))
        assertTrue(context.contains("identity"))
        assertTrue(context.contains("name: Jordan"))
        assertTrue(context.contains("preferences"))
        assertTrue(context.contains("food: pizza"))
    }

    @Test
    fun semanticMemoryBuildContextEmpty() = runTest {
        val memory = SemanticMemory(factDao)
        val context = memory.buildContext()
        assertEquals("", context)
    }
}
