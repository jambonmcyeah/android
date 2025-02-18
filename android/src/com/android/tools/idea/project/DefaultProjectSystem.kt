/*
 * Copyright (C) 2017 The Android Open Source Project
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
package com.android.tools.idea.project

import com.android.tools.apk.analyzer.AaptInvoker
import com.android.tools.idea.flags.StudioFlags
import com.android.tools.idea.log.LogWrapper
import com.android.tools.idea.projectsystem.*
import com.android.tools.idea.projectsystem.ProjectSystemSyncManager.SyncReason
import com.android.tools.idea.projectsystem.ProjectSystemSyncManager.SyncResult
import com.android.tools.idea.res.AndroidManifestClassPsiElementFinder
import com.android.tools.idea.res.AndroidResourceClassPsiElementFinder
import com.android.tools.idea.res.ProjectLightResourceClassService
import com.android.tools.idea.res.AndroidInnerClassFinder
import com.android.tools.idea.sdk.AndroidSdks
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.intellij.openapi.module.Module
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElementFinder
import com.intellij.ui.AppUIUtil
import java.nio.file.Path

/**
 * This implementation of AndroidProjectSystem is used for projects where the build system is not
 * recognized. It provides a minimal set of capabilities and opts out of most optional behaviors.
 */
class DefaultProjectSystem(val project: Project) : AndroidProjectSystem, AndroidProjectSystemProvider {
  override val id: String = ""

  override fun getDefaultApkFile(): VirtualFile? = null

  override fun getPathToAapt(): Path {
    return AaptInvoker.getPathToAapt(AndroidSdks.getInstance().tryToChooseSdkHandler(), LogWrapper(DefaultProjectSystem::class.java))
  }

  override fun isApplicable() = false

  override fun allowsFileCreation() = false

  override fun buildProject() {
  }

  override fun getSyncManager(): ProjectSystemSyncManager = object: ProjectSystemSyncManager {
    override fun syncProject(reason: SyncReason, requireSourceGeneration: Boolean): ListenableFuture<SyncResult> {
      AppUIUtil.invokeLaterIfProjectAlive(project) {
        project.messageBus.syncPublisher(PROJECT_SYSTEM_SYNC_TOPIC).syncEnded(SyncResult.SUCCESS)
      }
      return Futures.immediateFuture(SyncResult.SUCCESS)
    }

    override fun isSyncInProgress() = false
    override fun isSyncNeeded() = false
    override fun getLastSyncResult() = SyncResult.SUCCESS
  }

  override fun mergeBuildFiles(dependencies: String, destinationContents: String, supportLibVersionFilter: String?): String {
    return destinationContents
  }

  override val projectSystem = this

  override fun upgradeProjectToSupportInstantRun(): Boolean {
    return false
  }

  override fun getModuleSystem(module: Module): AndroidModuleSystem = DefaultModuleSystem(module)

  override fun getPsiElementFinders(): List<PsiElementFinder> {
    return if (StudioFlags.IN_MEMORY_R_CLASSES.get()) {
      listOf(
        AndroidInnerClassFinder.INSTANCE,
        AndroidManifestClassPsiElementFinder.getInstance(project),
        AndroidResourceClassPsiElementFinder(getLightResourceClassService())
      )
    } else {
      listOf(AndroidInnerClassFinder.INSTANCE)
    }
  }

  override fun getAugmentRClasses() = !StudioFlags.IN_MEMORY_R_CLASSES.get()

  override fun getLightResourceClassService() = ProjectLightResourceClassService.getInstance(project)
}
