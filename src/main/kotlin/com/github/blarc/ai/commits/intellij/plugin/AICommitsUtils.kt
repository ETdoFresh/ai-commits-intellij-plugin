package com.github.blarc.ai.commits.intellij.plugin

import com.github.blarc.ai.commits.intellij.plugin.notifications.Notification
import com.github.blarc.ai.commits.intellij.plugin.notifications.sendNotification
import com.github.blarc.ai.commits.intellij.plugin.settings.AppSettings
import com.github.blarc.ai.commits.intellij.plugin.settings.ProjectSettings
import com.intellij.dvcs.repo.Repository
import com.intellij.dvcs.repo.VcsRepositoryManager
import com.intellij.openapi.components.service
import com.intellij.openapi.diff.impl.patch.IdeaTextPatchBuilder
import com.intellij.openapi.diff.impl.patch.UnifiedDiffWriter
import com.intellij.openapi.project.Project
import com.intellij.openapi.vcs.AbstractVcs
import com.intellij.openapi.vcs.changes.Change
import com.intellij.openapi.vfs.VirtualFile
import com.knuddels.jtokkit.Encodings
import com.knuddels.jtokkit.api.ModelType
import java.io.StringWriter
import java.nio.file.FileSystems

object AICommitsUtils {

    fun isPathExcluded(path: String, project: Project) : Boolean {
        return !AppSettings.instance.isPathExcluded(path) && !project.service<ProjectSettings>().isPathExcluded(path)
    }

    fun matchesGlobs(text: String, globs: Set<String>): Boolean {
        val fileSystem = FileSystems.getDefault()
        for (globString in globs) {
            val glob = fileSystem.getPathMatcher("glob:$globString")
            if (glob.matches(fileSystem.getPath(text))) {
                return true
            }
        }
        return false
    }

    fun constructPrompt(promptContent: String, diff: String, branch: String): String {
        var content = promptContent
        content = content.replace("{locale}", AppSettings.instance.locale.displayLanguage)
        content = content.replace("{branch}", branch)

        return if (content.contains("{diff}")) {
            content.replace("{diff}", diff)
        } else {
            "$content\n$diff"
        }
    }

    fun commonBranch(changes: List<Change>, project: Project): String {
        val repositoryManager = VcsRepositoryManager.getInstance(project)
        var branch = changes.map {
            repositoryManager.getRepositoryForFileQuick(it.virtualFile)?.currentBranchName
        }.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key

        if (branch == null) {
            sendNotification(Notification.noCommonBranch())
            // hardcoded fallback branch
            branch = "main"
        }
        return branch
    }

    fun computeDiff(
            includedChanges: List<Change>,
            reversePatch: Boolean,
            project: Project
    ): String {

        val repositoryManager = VcsRepositoryManager.getInstance(project)

        val repository = SimpleRepository;
        repository._state = Repository.State.NORMAL
        repository._currentBranchName = "main"
        repository._currentRevision = "HEAD"
        repository._fresh = true
        repository._project = project
        repository._root = project.baseDir!!
        repositoryManager.repositories.add(repository)

        // go through included changes, create a map of repository to changes and discard nulls
        val changesByRepository = includedChanges
                .filter {
                    it.virtualFile?.path?.let { path ->
                        AICommitsUtils.isPathExcluded(path, project)
                    } ?: false
                }
                .mapNotNull { change ->
                        if (repositoryManager.repositories.isEmpty()) {
                            repository to change
                        }
                        else if (repositoryManager.repositories.size == 1) {
                            repositoryManager.repositories.first() to change
                        }
                        else {
                            change.virtualFile?.let { file ->
                                repositoryManager.getRepositoryForFileQuick(file) to change
                            }
                        }
                }
                .groupBy({ it.first }, { it.second })


        // compute diff for each repository
        return changesByRepository
                .map { (repository, changes) ->
                    repository?.let {
                        val filePatches = IdeaTextPatchBuilder.buildPatch(
                                project,
                                changes,
                                repository.root.toNioPath(), reversePatch, true
                        )

                        val stringWriter = StringWriter()
                        stringWriter.write("Repository: ${repository.root.path}\n")
                        UnifiedDiffWriter.write(project, filePatches, stringWriter, "\n", null)
                        stringWriter.toString()
                    }
                }
                .joinToString("\n")
    }

    fun isPromptTooLarge(prompt: String): Boolean {
        val registry = Encodings.newDefaultEncodingRegistry()

        /*
         * Try to find the model type based on the model id by finding the longest matching model type
         * If no model type matches, let the request go through and let the OpenAI API handle it
         */
        val modelType = ModelType.entries
            .filter { AppSettings.instance.openAIModelId.contains(it.name) }
            .maxByOrNull { it.name.length }
            ?: return false

        val encoding = registry.getEncoding(modelType.encodingType)
        return encoding.countTokens(prompt) > modelType.maxContextLength
    }
}

object SimpleRepository : Repository {
    var _project: Project? = null
    var _vcs: AbstractVcs? = null
    var _root: VirtualFile? = null
    var _state: Repository.State = Repository.State.DETACHED
    var _currentBranchName: String? = ""
    var _currentRevision: String? = ""
    var _fresh: Boolean = false

    override fun dispose() {
        // do nothing
    }

    override fun getRoot(): VirtualFile {
        return _root ?: throw IllegalStateException("Root not set")
    }

    override fun getPresentableUrl(): String {
        return _root?.path ?: ""
    }

    override fun getProject(): Project {
        return _project ?: throw IllegalStateException("Project not set")
    }

    override fun getState(): Repository.State {
        return _state
    }

    override fun getCurrentBranchName(): String? {
        return _currentBranchName
    }

    override fun getVcs(): AbstractVcs {
        return _vcs ?: throw IllegalStateException("VCS not set")
    }

    override fun getCurrentRevision(): String? {
        return _currentRevision
    }

    override fun isFresh(): Boolean {
        return _fresh
    }

    override fun update() {
        // do nothing
    }

    override fun toLogString(): String {
        return _root?.path ?: ""
    }
}