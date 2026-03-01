package com.cellclaw.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.cellclaw.skills.SkillListing
import com.cellclaw.skills.SkillRegistry
import com.cellclaw.skills.SkillStoreRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SkillStoreViewModel @Inject constructor(
    private val storeRepository: SkillStoreRepository,
    private val skillRegistry: SkillRegistry
) : ViewModel() {

    private val _listings = MutableStateFlow<List<SkillListing>>(emptyList())
    val listings: StateFlow<List<SkillListing>> = _listings.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    /** Track which skills are being installed (show loading state on button) */
    private val _installing = MutableStateFlow<Set<String>>(emptySet())
    val installing: StateFlow<Set<String>> = _installing.asStateFlow()

    // Expose installed skills for checking install state
    val installedSkills = skillRegistry.skills

    init {
        fetchIndex()
    }

    fun fetchIndex(forceRefresh: Boolean = false) {
        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null

            storeRepository.fetchIndex(forceRefresh).fold(
                onSuccess = { _listings.value = it },
                onFailure = { _error.value = it.message ?: "Failed to load skills" }
            )

            _isLoading.value = false
        }
    }

    fun installSkill(listing: SkillListing) {
        viewModelScope.launch {
            _installing.value = _installing.value + listing.slug

            storeRepository.installSkill(listing).fold(
                onSuccess = { /* skill is now in registry */ },
                onFailure = { _error.value = "Failed to install ${listing.name}: ${it.message}" }
            )

            _installing.value = _installing.value - listing.slug
        }
    }

    fun uninstallSkill(listing: SkillListing) {
        storeRepository.uninstallSkill(listing.name)
    }

    fun isInstalled(listing: SkillListing): Boolean {
        return storeRepository.isInstalled(listing)
    }

    fun dismissError() {
        _error.value = null
    }
}
