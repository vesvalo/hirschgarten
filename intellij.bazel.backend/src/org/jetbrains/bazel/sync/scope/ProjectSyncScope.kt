package org.jetbrains.bazel.sync.scope

import org.jetbrains.annotations.ApiStatus
import org.jetbrains.bazel.label.Label
import java.nio.file.Path

/**
 * Scope of the sync. Multiple versions of sync are supported, including
 * - full project syncs (all targets are re-synced)
 * - partial syncs (only a subset of targets is re-synced).
 */
// TODO: remove completely on monolithic sync due to being unused and broken
@ApiStatus.Internal
sealed interface ProjectSyncScope

/**
 * Represents all the syncs which are re-syncing the whole project, and all the targets should be refreshed.
 */
@ApiStatus.Internal
sealed interface FullProjectSync : ProjectSyncScope

/**
 * Represents the first phase of the phased sync - the quick sync after which the project is in the incomplete mode
 */
@ApiStatus.Internal
data object FirstPhaseSync : FullProjectSync

/**
 * Represents the second phase of the phased sync - the "heavy" sync after which the project is in its final form
 */
@ApiStatus.Internal
data object SecondPhaseSync : FullProjectSync

/**
 * Represents a partial project sync, which operates only on a limited subset of targets,
 * and only things related to these targets should be refreshed
 *
 * @property userRequestedTargets The targets explicitly requested by the user to sync
 * @property resolvedTargets The targets that were actually resolved (userRequestedTargets + transitive deps), populated after resolution
 */
@ApiStatus.Internal
data class PartialProjectSync(
  val userRequestedTargets: List<Label>,
) : ProjectSyncScope {
  var resolvedTargets: List<Label> = userRequestedTargets
    internal set
}

/**
 * Represents a sync based on modified files.
 * The sync resolves which targets own those files and syncs them along with their dependencies.
 *
 * @property files The modified files to resolve targets for
 * @property build Whether to build the targets during sync
 */
@ApiStatus.Internal
data class FilesProjectSync(
  val files: List<Path>,
  val build: Boolean,
) : ProjectSyncScope {
  var resolvedTargets: List<Label> = emptyList()
    internal set
}
