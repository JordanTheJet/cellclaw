package com.cellclaw.ui.viewmodel

import androidx.lifecycle.ViewModel
import com.cellclaw.skills.Skill
import com.cellclaw.skills.SkillRegistry
import com.cellclaw.skills.SkillSource
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

@HiltViewModel
class SkillsViewModel @Inject constructor(
    private val skillRegistry: SkillRegistry
) : ViewModel() {

    val skills: StateFlow<List<Skill>> = skillRegistry.skills

    init {
        skillRegistry.loadBundledSkills()
    }

    fun addSkill(name: String, description: String, trigger: String, stepsText: String) {
        val steps = stepsText.lines().filter { it.isNotBlank() }
        val skill = Skill(
            name = name,
            description = description,
            trigger = trigger,
            steps = steps,
            source = SkillSource.USER_CREATED
        )
        skillRegistry.addSkill(skill)
    }

    fun removeSkill(name: String) {
        skillRegistry.removeSkill(name)
    }
}
