/*
 * Copyright (C) 2018 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.tools.idea.common.model

import com.android.ide.common.repository.GradleCoordinate
import com.android.ide.common.repository.GradleVersion
import com.android.tools.idea.projectsystem.*
import com.android.tools.idea.util.addDependencies
import com.android.tools.idea.util.dependsOn
import com.android.tools.idea.util.userWantsToAdd
import com.google.common.util.concurrent.ListenableFuture
import com.intellij.openapi.application.ex.ApplicationManagerEx
import org.jetbrains.android.facet.AndroidFacet

/**
 * Handles dependencies for the Layout Editor.
 *
 * This class acts as an abstraction layer between Layout Editor component and the build system to manage
 * dependencies required by the provided [NlComponent]
 */
class NlDependencyManager {

  companion object {
    fun get() = NlDependencyManager()
  }

  /**
   * Make sure the dependencies of the components being added are present and resolved in the module.
   * If they are not: ask the user if they can be added now.
   * Return true if the dependencies are present now (they may have just been added).
   */
  @JvmOverloads
  fun addDependencies(components: Iterable<NlComponent>,
                      facet: AndroidFacet,
                      syncDoneCallback: (() -> Unit)? = null): Boolean {
    val moduleSystem = facet.module.getModuleSystem()
    val missingDependencies = collectDependencies(components).filter { moduleSystem.getRegisteredDependency(it) == null }
    if (missingDependencies.isEmpty()) {
      syncDoneCallback?.invoke()
      return true
    }

    if (facet.module.addDependencies(missingDependencies, false, false).isNotEmpty()) {
      return false
    }

    val syncResult: ListenableFuture<ProjectSystemSyncManager.SyncResult> =
      facet.module.project.getSyncManager().syncProject(ProjectSystemSyncManager.SyncReason.PROJECT_MODIFIED, true)

    if (syncDoneCallback != null) {
      syncResult.addCallback(success = { _ -> syncDoneCallback() }, failure = { _ -> syncDoneCallback() })
    }

    return true
  }

  /**
   * Returns true if the current module depends on the specified library.
   */
  fun isModuleDependency(artifact: GoogleMavenArtifactId, facet: AndroidFacet): Boolean = facet.module.dependsOn(artifact)

  /**
   * Returns the [GradleVersion] of the library with specified [artifactId] that the current module depends on.
   * @return the revision or null if the module does not depend on the specified library or the maven version is unknown.
   */
  fun getModuleDependencyVersion(artifactId: GoogleMavenArtifactId, facet: AndroidFacet): GradleVersion? =
      facet.module.getModuleSystem().getResolvedDependency(artifactId.getCoordinate("+"))?.version

  /**
   * Check if there is any missing dependencies and ask the user only if they are some.
   *
   * User cannot be asked to accept dependencies in a write action.
   * Calls to this method should be made outside a write action, or all dependencies should already be added.
   */
  fun checkIfUserWantsToAddDependencies(toAdd: List<NlComponent>, facet: AndroidFacet): Boolean {
    val dependencies = collectDependencies(toAdd)
    val moduleSystem = facet.module.getModuleSystem()
    val missing = dependencies.filter { moduleSystem.getRegisteredDependency(it) == null }
    if (missing.none()) {
      return true
    }

    val application = ApplicationManagerEx.getApplicationEx()
    if (application.isWriteActionInProgress) {
      kotlin.assert(false) {
        "User cannot be asked to accept dependencies in a write action." +
            "Calls to this method should be made outside a write action"
      }
      return true
    }

    return userWantsToAdd(facet.module.project, missing)
  }

  /**
   * Find all dependencies for the given components and map them to a [GradleCoordinate]
   * @see GradleCoordinate.parseCoordinateString()
   */
  private fun collectDependencies(components: Iterable<NlComponent>): Iterable<GradleCoordinate> {
    return components
        .flatMap { it.dependencies}
        .mapNotNull { artifact -> GradleCoordinate.parseCoordinateString(artifact + ":+") }
        .toList()
  }
}
