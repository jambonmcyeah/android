/*
 * Copyright (C) 2016 The Android Open Source Project
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
package com.android.tools.idea.gradle.project.importing;

import com.android.tools.idea.IdeInfo;
import com.android.tools.idea.gradle.project.GradleProjectInfo;
import com.android.tools.idea.gradle.project.sync.GradleSyncInvoker;
import com.android.tools.idea.gradle.project.sync.GradleSyncListener;
import com.android.tools.idea.gradle.project.sync.SdkSync;
import com.android.tools.idea.gradle.project.sync.ng.nosyncbuilder.misc.NewProjectExtraInfo;
import com.android.tools.idea.gradle.util.LocalProperties;
import com.google.common.annotations.VisibleForTesting;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.TransactionGuard;
import com.intellij.openapi.components.ServiceManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.pom.java.LanguageLevel;
import com.intellij.serviceContainer.NonInjectable;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.plugins.gradle.settings.GradleProjectSettings;
import org.jetbrains.plugins.gradle.settings.GradleSettings;

import java.io.File;
import java.io.IOException;
import java.util.HashSet;
import java.util.Set;

import static com.android.tools.idea.util.ToolWindows.activateProjectView;
import static com.intellij.ide.impl.ProjectUtil.focusProjectWindow;
import static com.intellij.openapi.externalSystem.util.ExternalSystemApiUtil.toCanonicalPath;
import static com.intellij.openapi.fileChooser.impl.FileChooserUtil.setLastOpenedFile;
import static com.intellij.openapi.ui.Messages.showErrorDialog;
import static com.intellij.openapi.vfs.VfsUtilCore.virtualToIoFile;
import static com.intellij.util.ExceptionUtil.rethrowUnchecked;

/**
 * Imports an Android-Gradle project without showing the "Import Project" Wizard UI.
 */
public final class GradleProjectImporter {
  @NotNull private final SdkSync mySdkSync;
  @NotNull private final GradleSyncInvoker myGradleSyncInvoker;
  @NotNull private final NewProjectSetup myNewProjectSetup;
  @NotNull private final ProjectFolder.Factory myProjectFolderFactory;

  /**
   * Flag used by unit tests to selectively disable code which requires an open project or UI updates; this is used
   * by unit tests that do not run all of IntelliJ (e.g. do not extend the IdeaTestCase base)
   */
  @SuppressWarnings("StaticNonFinalField") public static boolean ourSkipSetupFromTest;

  @NotNull
  public static GradleProjectImporter getInstance() {
    return ServiceManager.getService(GradleProjectImporter.class);
  }

  public GradleProjectImporter() {
    this(SdkSync.getInstance(), GradleSyncInvoker.getInstance(), new NewProjectSetup(), new ProjectFolder.Factory());
  }

  @VisibleForTesting
  @NonInjectable
  GradleProjectImporter(@NotNull SdkSync sdkSync,
                        @NotNull GradleSyncInvoker gradleSyncInvoker,
                        @NotNull NewProjectSetup newProjectSetup,
                        @NotNull ProjectFolder.Factory projectFolderFactory) {
    mySdkSync = sdkSync;
    myGradleSyncInvoker = gradleSyncInvoker;
    myNewProjectSetup = newProjectSetup;
    myProjectFolderFactory = projectFolderFactory;
  }

  public void openProject(@NotNull VirtualFile selectedFile) {
    openOrImportProject(selectedFile, true /* open project */);
  }

  /**
   * Imports the given Gradle project.
   *
   * @param selectedFile the selected build.gradle or the project's root directory.
   */
  public void importProject(@NotNull VirtualFile selectedFile) {
    openOrImportProject(selectedFile, false /* import project */);
  }

  private void openOrImportProject(@NotNull VirtualFile selectedFile, boolean openProject) {
    VirtualFile projectFolder = findProjectFolder(selectedFile);
    File projectFolderPath = virtualToIoFile(projectFolder);
    try {
      setUpLocalProperties(projectFolderPath);
    }
    catch (IOException e) {
      return;
    }
    try {
      String projectName = projectFolder.getName();
      openOrImportProject(projectName, projectFolderPath, Request.EMPTY_REQUEST, createNewProjectListener(projectFolder), openProject);
    }
    catch (Throwable e) {
      if (ApplicationManager.getApplication().isUnitTestMode()) {
        rethrowUnchecked(e);
      }
      showErrorDialog(e.getMessage(), openProject ? "Open Project" : "Project Import");
      getLogger().error(e);
    }
  }

  @NotNull
  private static VirtualFile findProjectFolder(@NotNull VirtualFile selectedFile) {
    return selectedFile.isDirectory() ? selectedFile : selectedFile.getParent();
  }

  private void setUpLocalProperties(@NotNull File projectFolderPath) throws IOException {
    try {
      LocalProperties localProperties = new LocalProperties(projectFolderPath);
      if (IdeInfo.getInstance().isAndroidStudio()) {
        mySdkSync.syncIdeAndProjectAndroidSdks(localProperties);
      }
    }
    catch (IOException e) {
      getLogger().info("Failed to sync SDKs", e);
      showErrorDialog(e.getMessage(), "Project Import");
      throw e;
    }
  }

  @NotNull
  private Logger getLogger() {
    return Logger.getInstance(getClass());
  }

  @NotNull
  private static GradleSyncListener createNewProjectListener(@NotNull VirtualFile projectFolder) {
    return new GradleSyncListener() {
      @Override
      public void syncSucceeded(@NotNull Project project) {
        TransactionGuard.getInstance().submitTransactionLater(project, () -> {
          setLastOpenedFile(project, projectFolder);
          focusProjectWindow(project, false);
          activateProjectView(project);
        });
      }
    };
  }

  public void importProject(@NotNull String projectName, @NotNull File projectFolderPath, @Nullable GradleSyncListener listener)
    throws IOException {
    importProject(projectName, projectFolderPath, Request.EMPTY_REQUEST, listener);
  }

  public void importProject(@NotNull String projectName,
                            @NotNull File projectFolderPath,
                            @NotNull Request request,
                            @Nullable GradleSyncListener listener) throws IOException {
    openOrImportProject(projectName, projectFolderPath, request, listener, false /* import project */);
  }

  private void openOrImportProject(@NotNull String projectName,
                                   @NotNull File projectFolderPath,
                                   @NotNull Request request,
                                   @Nullable GradleSyncListener listener,
                                   boolean openProject) throws IOException {
    ProjectFolder projectFolder = myProjectFolderFactory.create(projectFolderPath);
    projectFolder.createTopLevelBuildFile();
    projectFolder.createIdeaProjectFolder();

    Project newProject = request.project;

    if (newProject == null) {
      newProject = openProject
                   ? myNewProjectSetup.openProject(projectFolderPath.getPath())
                   : myNewProjectSetup.createProject(projectName, projectFolderPath.getPath());
      GradleSettings gradleSettings = GradleSettings.getInstance(newProject);
      gradleSettings.setGradleVmOptions("");

      String externalProjectPath = toCanonicalPath(projectFolderPath.getPath());
      GradleProjectSettings projectSettings = gradleSettings.getLinkedProjectSettings(externalProjectPath);
      if (projectSettings == null) {
        Set<GradleProjectSettings> projects =
          new HashSet<>(gradleSettings.getLinkedProjectsSettings());
        projectSettings = new GradleProjectSettings();
        projectSettings.setExternalProjectPath(externalProjectPath);
        projects.add(projectSettings);
        gradleSettings.setLinkedProjectsSettings(projects);
      }
    }

    GradleProjectInfo projectInfo = GradleProjectInfo.getInstance(newProject);
    projectInfo.setNewProject(request.isNewProject);
    projectInfo.setImportedProject(true);
    projectInfo.setExtraInfo(request.extraInfo);
    projectInfo.setSkipStartupActivity(true);

    myNewProjectSetup.prepareProjectForImport(newProject, request.javaLanguageLevel, openProject);

    if (!ApplicationManager.getApplication().isUnitTestMode()) {
      newProject.save();
    }

    myGradleSyncInvoker.requestProjectSync(newProject, createSyncRequestSettings(request), listener);
  }

  @NotNull
  private static GradleSyncInvoker.Request createSyncRequestSettings(@NotNull Request importProjectRequest) {
    GradleSyncInvoker.Request request = GradleSyncInvoker.Request.projectLoaded();
    request.generateSourcesOnSuccess = importProjectRequest.generateSourcesOnSuccess;
    request.useCachedGradleModels = false;
    return request;
  }

  public static class Request {
    @NotNull private static final Request EMPTY_REQUEST = new Request();

    @Nullable public Project project;
    @Nullable public LanguageLevel javaLanguageLevel;
    @Nullable public NewProjectExtraInfo extraInfo;

    public boolean generateSourcesOnSuccess = true;
    public boolean isNewProject;
  }
}
