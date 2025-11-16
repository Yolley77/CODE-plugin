package ru.codeplugin.services

import com.intellij.openapi.components.*
import com.intellij.openapi.project.Project

class CodeBranchLifecycleState {
    var branchStartTimes: MutableMap<String, Long> = mutableMapOf()
}

@Service(Service.Level.PROJECT)
@State(
    name = "CodeBranchLifecycle",
    storages = [Storage("code-branch-lifecycle.xml")]
)
class CodeBranchLifecycleService(private val project: Project) :
    PersistentStateComponent<CodeBranchLifecycleState> {

    private var state = CodeBranchLifecycleState()

    override fun getState(): CodeBranchLifecycleState = state

    override fun loadState(state: CodeBranchLifecycleState) {
        this.state = state
    }

    fun markBranchSeen(branch: String) {
        if (!state.branchStartTimes.containsKey(branch)) {
            state.branchStartTimes[branch] = System.currentTimeMillis()
        }
    }

    fun getBranchAgeHours(branch: String): Double? {
        val start = state.branchStartTimes[branch] ?: return null
        val now = System.currentTimeMillis()
        val diffMs = now - start
        return diffMs / 1000.0 / 60.0 / 60.0
    }

    companion object {
        fun getInstance(project: Project): CodeBranchLifecycleService =
            project.service()
    }
}
