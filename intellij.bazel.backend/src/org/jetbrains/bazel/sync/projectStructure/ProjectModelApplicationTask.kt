package org.jetbrains.bazel.sync.projectStructure

import com.intellij.platform.workspace.jps.entities.ModuleEntity
import com.intellij.platform.workspace.jps.entities.SourceRootEntity
import com.intellij.platform.workspace.storage.EntitySource
import com.intellij.platform.workspace.storage.MutableEntityStorage
import com.intellij.platform.workspace.storage.WorkspaceEntity
import com.intellij.openapi.components.serviceAsync
import com.intellij.openapi.project.Project
import com.intellij.platform.backend.workspace.WorkspaceModel
import com.intellij.platform.diagnostic.telemetry.helpers.use
import com.intellij.platform.diagnostic.telemetry.helpers.useWithScope
import com.intellij.workspaceModel.ide.impl.WorkspaceModelImpl
import org.jetbrains.bazel.config.BazelBackendBundle
import org.jetbrains.bazel.label.Label
import org.jetbrains.bazel.performance.bspTracer
import org.jetbrains.bazel.progress.syncConsole
import org.jetbrains.bazel.progress.withSubtask
import org.jetbrains.bazel.sync.scope.FilesProjectSync
import org.jetbrains.bazel.sync.scope.FullProjectSync
import org.jetbrains.bazel.sync.scope.PartialProjectSync
import org.jetbrains.bazel.sync.scope.ProjectSyncScope
import org.jetbrains.bazel.workspacemodel.entities.BazelDummyEntitySource
import org.jetbrains.bazel.workspacemodel.entities.BazelEntitySource
import org.jetbrains.bazel.workspacemodel.entities.BazelModuleEntitySource
import org.jetbrains.bazel.workspacemodel.entities.BazelModuleExtensionEntity
import org.jetbrains.bazel.workspacemodel.entities.BazelProjectEntitySource
import org.jetbrains.bsp.protocol.TaskId

internal class ProjectModelApplicationTask(
  private val project: Project,
  private val scope: ProjectSyncScope,
  private val taskId: TaskId,
  private val postActions: List<suspend () -> Unit>,
) {
  companion object {
    private const val MAX_REPLACE_WSM_ATTEMPTS = 3
  }

  suspend fun apply(storage: MutableEntityStorage) {
    when (scope) {
      is FullProjectSync -> applyFullSync(storage)
      is PartialProjectSync -> applyPartialSync(storage, scope.resolvedTargets)
      is FilesProjectSync -> applyPartialSync(storage, scope.resolvedTargets)
    }

    postActions.forEach { it() }
  }

  private suspend fun applyFullSync(storage: MutableEntityStorage) {
    fun MutableEntityStorage.replaceBySource() {
      replaceBySource(
        sourceFilter = { entitySource: EntitySource -> entitySource is BazelEntitySource },
        replaceWith = storage,
      )
    }

    project.syncConsole.withSubtask(
      subtaskId = taskId.subTask("apply-changes-on-workspace-model"),
      message = BazelBackendBundle.message("console.task.model.apply.changes"),
    ) {
      bspTracer.spanBuilder("apply.changes.on.workspace.model.ms").useWithScope {
        val workspaceModel = project.serviceAsync<WorkspaceModel>() as WorkspaceModelImpl
        workspaceModel.updateWithRetry(
          BazelBackendBundle.message("console.task.model.apply.changes.attempt.0.1.wsm", 0, 0),
          MAX_REPLACE_WSM_ATTEMPTS,
        ) { builder ->
          bspTracer.spanBuilder("replaceprojectmodel.in.apply.on.workspace.model.ms").use {
            builder.replaceBySource()
          }
        }
      }
    }
  }

  private suspend fun applyPartialSync(storage: MutableEntityStorage, targetsToSync: List<Label>) {
    val syncedLabels = targetsToSync.toSet()

    fun MutableEntityStorage.removeEntitiesFromSyncedTargets() {
      val modulesToRemove = entities(ModuleEntity::class.java).filter { it.entitySource is BazelModuleEntitySource }
      val sourcesToRemove = entities(SourceRootEntity::class.java).filter { it.entitySource is BazelModuleEntitySource }
      modulesToRemove.forEach { removeEntity(it) }
      sourcesToRemove.forEach { removeEntity(it) }
    }

    fun ModuleEntity.matchesSyncedTarget(): Boolean {
      val extension = bazelModuleExtension ?: return false
      return extension.targetKey.label in syncedLabels
    }

    project.syncConsole.withSubtask(
      subtaskId = taskId.subTask("apply-changes-on-workspace-model"),
      message = BazelBackendBundle.message("console.task.model.apply.changes"),
    ) {
      bspTracer.spanBuilder("apply.changes.on.workspace.model.ms").useWithScope {
        val workspaceModel = project.serviceAsync<WorkspaceModel>() as WorkspaceModelImpl
        workspaceModel.updateWithRetry(
          BazelBackendBundle.message("console.task.model.apply.changes.attempt.0.1.wsm", 0, 0),
          MAX_REPLACE_WSM_ATTEMPTS,
        ) { builder ->
          bspTracer.spanBuilder("remove.entities.in.apply.on.workspace.model.ms").use {
            builder.removeEntitiesFromSyncedTargets()
          }
          bspTracer.spanBuilder("replaceprojectmodel.in.apply.on.workspace.model.ms").use {
            builder.replaceBySource(
              sourceFilter = { entitySource: EntitySource -> entitySource is BazelProjectEntitySource },
              replaceWith = storage,
            )
          }
          bspTracer.spanBuilder("add.partial.sync.entities.in.apply.on.workspace.model.ms").use {
            val modulesToAdd = storage.entities(ModuleEntity::class.java).filter { it.matchesSyncedTarget() }
            modulesToAdd.forEach { builder.addEntity(it) }
          }
        }
      }
    }
  }
}
