package com.cellclaw.memory

import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SemanticMemory @Inject constructor(
    private val factDao: MemoryFactDao
) {
    suspend fun remember(key: String, value: String, category: String = "general") {
        factDao.upsert(
            MemoryFactEntity(
                key = key,
                value = value,
                category = category
            )
        )
    }

    suspend fun recall(category: String): List<MemoryFactEntity> {
        return factDao.getByCategory(category)
    }

    suspend fun recallAll(): List<MemoryFactEntity> {
        return factDao.getAll()
    }

    suspend fun search(query: String): List<MemoryFactEntity> {
        return factDao.search(query)
    }

    suspend fun forget(id: Long) {
        factDao.delete(id)
    }

    /** Build a context string for inclusion in system prompts */
    suspend fun buildContext(): String {
        val facts = factDao.getAll()
        if (facts.isEmpty()) return ""

        return buildString {
            appendLine("\n## Known Facts")
            facts.groupBy { it.category }.forEach { (category, categoryFacts) ->
                appendLine("### $category")
                categoryFacts.forEach { fact ->
                    appendLine("- ${fact.key}: ${fact.value}")
                }
            }
        }
    }
}
