package com.cellclaw.skills

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
data class SkillListing(
    val slug: String,
    val name: String,
    val description: String,
    val category: String = "",
    val author: String = "community",
    val version: String = "1.0.0",
    val file: String,
    val tools: List<String> = emptyList(),
    val apps: List<String> = emptyList(),
    val downloads: Int = 0
)

@Serializable
private data class SkillIndex(
    val version: Int = 1,
    val updated: String = "",
    val skills: List<SkillListing> = emptyList()
)

@Singleton
class SkillStoreRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val skillRegistry: SkillRegistry,
    private val parser: SkillParser
) {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val json = Json { ignoreUnknownKeys = true }

    private var cachedIndex: List<SkillListing>? = null
    private var cacheTimestamp = 0L

    companion object {
        private const val BASE_URL = "https://raw.githubusercontent.com/jordanthejet/cellclaw-skills/main"
        private const val CACHE_TTL_MS = 60 * 60 * 1000L // 1 hour
        private const val CACHE_FILE = "skill_store_cache.json"
    }

    /** Fetch the skill index from GitHub. Uses cache if fresh. */
    suspend fun fetchIndex(forceRefresh: Boolean = false): Result<List<SkillListing>> {
        // Return memory cache if fresh
        if (!forceRefresh && cachedIndex != null &&
            System.currentTimeMillis() - cacheTimestamp < CACHE_TTL_MS) {
            return Result.success(cachedIndex!!)
        }

        // Try disk cache if memory cache is stale
        if (!forceRefresh) {
            loadDiskCache()?.let { cached ->
                cachedIndex = cached
                return Result.success(cached)
            }
        }

        // Fetch from GitHub
        return withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder()
                    .url("$BASE_URL/index.json")
                    .build()

                val response = client.newCall(request).execute()
                if (!response.isSuccessful) {
                    // Fall back to disk cache on network error
                    loadDiskCache()?.let { cached ->
                        cachedIndex = cached
                        return@withContext Result.success(cached)
                    }
                    return@withContext Result.failure(Exception("Failed to fetch skill index: ${response.code}"))
                }

                val body = response.body?.string()
                    ?: return@withContext Result.failure(Exception("Empty response"))

                val index = json.decodeFromString<SkillIndex>(body)
                cachedIndex = index.skills
                cacheTimestamp = System.currentTimeMillis()

                // Save to disk cache
                saveDiskCache(body)

                Result.success(index.skills)
            } catch (e: Exception) {
                // Fall back to disk cache
                loadDiskCache()?.let { cached ->
                    cachedIndex = cached
                    return@withContext Result.success(cached)
                }
                Result.failure(e)
            }
        }
    }

    /** Install a skill from the registry */
    suspend fun installSkill(listing: SkillListing): Result<Unit> {
        return withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder()
                    .url("$BASE_URL/${listing.file}")
                    .build()

                val response = client.newCall(request).execute()
                if (!response.isSuccessful) {
                    return@withContext Result.failure(Exception("Failed to download skill: ${response.code}"))
                }

                val content = response.body?.string()
                    ?: return@withContext Result.failure(Exception("Empty skill content"))

                // Parse and save with COMMUNITY source
                val skill = parser.parse(content)
                    ?: return@withContext Result.failure(Exception("Failed to parse skill markdown"))

                val communitySkill = skill.copy(source = SkillSource.COMMUNITY)
                skillRegistry.addSkill(communitySkill)

                Result.success(Unit)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    /** Uninstall a community skill */
    fun uninstallSkill(name: String) {
        skillRegistry.removeSkill(name)
    }

    /** Check if a skill is installed (by slug matching to skill name) */
    fun isInstalled(listing: SkillListing): Boolean {
        return skillRegistry.skills.value.any {
            it.name.equals(listing.name, ignoreCase = true)
        }
    }

    private fun loadDiskCache(): List<SkillListing>? {
        return try {
            val cacheFile = File(context.cacheDir, CACHE_FILE)
            if (!cacheFile.exists()) return null

            // Check if cache file is too old (24 hours max for disk)
            if (System.currentTimeMillis() - cacheFile.lastModified() > 24 * 60 * 60 * 1000L) {
                return null
            }

            val body = cacheFile.readText()
            val index = json.decodeFromString<SkillIndex>(body)
            cacheTimestamp = cacheFile.lastModified()
            index.skills
        } catch (_: Exception) {
            null
        }
    }

    private fun saveDiskCache(body: String) {
        try {
            val cacheFile = File(context.cacheDir, CACHE_FILE)
            cacheFile.writeText(body)
        } catch (_: Exception) {
            // Cache save failure is non-critical
        }
    }
}
