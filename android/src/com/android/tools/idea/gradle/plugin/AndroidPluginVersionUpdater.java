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
package com.android.tools.idea.gradle.plugin;

import com.android.ide.common.repository.GradleVersion;
import com.android.tools.idea.gradle.dsl.api.GradleBuildModel;
import com.android.tools.idea.gradle.dsl.api.dependencies.ArtifactDependencyModel;
import com.android.tools.idea.gradle.dsl.api.dependencies.DependenciesModel;
import com.android.tools.idea.gradle.project.sync.GradleSyncInvoker;
import com.android.tools.idea.gradle.project.sync.GradleSyncState;
import com.android.tools.idea.gradle.util.BuildFileProcessor;
import com.android.tools.idea.gradle.util.GradleWrapper;
import com.google.common.annotations.VisibleForTesting;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.ServiceManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.vfs.VirtualFileManager;
import com.intellij.serviceContainer.NonInjectable;
import com.intellij.util.ThreeState;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import static com.android.tools.idea.gradle.dsl.api.dependencies.CommonConfigurationNames.CLASSPATH;
import static com.android.tools.idea.gradle.project.sync.hyperlink.SearchInBuildFilesHyperlink.searchInBuildFiles;
import static com.android.tools.idea.gradle.util.BuildFileProcessor.getCompositeBuildFolderPaths;
import static com.android.tools.idea.gradle.util.GradleUtil.isSupportedGradleVersion;
import static com.android.tools.idea.gradle.util.GradleWrapper.getDefaultPropertiesFilePath;
import static com.android.utils.FileUtils.toSystemDependentPath;
import static com.intellij.openapi.command.WriteCommandAction.runWriteCommandAction;
import static com.intellij.openapi.util.text.StringUtil.isEmpty;
import static com.intellij.openapi.util.text.StringUtil.isNotEmpty;
import static com.intellij.util.ThreeState.*;

public class AndroidPluginVersionUpdater {
  @NotNull private final Project myProject;
  @NotNull private final GradleSyncState mySyncState;
  @NotNull private final TextSearch myTextSearch;

  @NotNull
  public static AndroidPluginVersionUpdater getInstance(@NotNull Project project) {
    return ServiceManager.getService(project, AndroidPluginVersionUpdater.class);
  }

  public AndroidPluginVersionUpdater(@NotNull Project project) {
    this(project, GradleSyncState.getInstance(project), new TextSearch(project));
  }

  @VisibleForTesting
  @NonInjectable
  AndroidPluginVersionUpdater(@NotNull Project project,
                              @NotNull GradleSyncState syncState,
                              @NotNull TextSearch textSearch) {
    myProject = project;
    mySyncState = syncState;
    myTextSearch = textSearch;
  }

  public boolean canDetectPluginVersionToUpdate(@NotNull GradleVersion pluginVersion) {
    final boolean[] foundPlugin = new boolean[1];
    BuildFileProcessor.getInstance().processRecursively(myProject, buildModel -> {
      if (foundPlugin[0]) {
        return true;
      }

      DependenciesModel dependencies = buildModel.buildscript().dependencies();
      for (ArtifactDependencyModel dependency : dependencies.artifacts(CLASSPATH)) {
        ThreeState result = isUpdatablePluginVersion(pluginVersion, dependency);
        if (result == YES) {
          foundPlugin[0] = true;
          break;
        }
        else if (result == NO) {
          break;
        }
      }
      return true;
    }, true);
    return foundPlugin[0];
  }

  public UpdateResult updatePluginVersionAndSync(@NotNull GradleVersion pluginVersion,
                                                 @Nullable GradleVersion gradleVersion,
                                                 boolean invalidateLastSyncOnFailure) {
    UpdateResult result = updatePluginVersion(pluginVersion, gradleVersion);

    Throwable pluginVersionUpdateError = result.getPluginVersionUpdateError();
    Throwable gradleVersionUpdateError = result.getGradleVersionUpdateError();

    if (pluginVersionUpdateError != null) {
      String msg = String.format("Failed to update Android plugin to version '%1$s'", pluginVersion);
      logUpdateError(msg, pluginVersionUpdateError);
    }
    if (gradleVersionUpdateError != null) {
      String msg = String.format("Failed to update Gradle to version '%1$s'", gradleVersion);
      logUpdateError(msg, gradleVersionUpdateError);
    }

    handleUpdateResult(result, invalidateLastSyncOnFailure);
    return result;
  }

  @VisibleForTesting
  void handleUpdateResult(@NotNull UpdateResult result, boolean invalidateLastSyncOnFailure) {
    Throwable pluginVersionUpdateError = result.getPluginVersionUpdateError();
    if (pluginVersionUpdateError != null || result.getGradleVersionUpdateError() != null) {
      if (invalidateLastSyncOnFailure) {
        mySyncState.invalidateLastSync("Failed to update either Android plugin version or Gradle version");
      }

      if (pluginVersionUpdateError != null) {
        myTextSearch.execute();
      }
    }
    else if (result.isPluginVersionUpdated() || result.isGradleVersionUpdated()) {
      // Update successful. Sync project.
      if (!mySyncState.lastSyncFailedOrHasIssues()) {
        mySyncState.syncEnded();
      }

      // TODO add a trigger when the plug-in version changed (right now let as something changed in the project)
      GradleSyncInvoker.Request request = GradleSyncInvoker.Request.projectModified();
      request.cleanProject = true;
      getGradleSyncInvoker().requestProjectSync(myProject, request);
    }
  }

  @NotNull
  protected GradleSyncInvoker getGradleSyncInvoker() {
    return GradleSyncInvoker.getInstance();
  }

  private static void logUpdateError(@NotNull String msg, @NotNull Throwable error) {
    String cause = error.getMessage();
    if (isNotEmpty(cause)) {
      msg += ": " + cause;
    }
    Logger.getInstance(AndroidPluginVersionUpdater.class).warn(msg);
  }

  /**
   * Updates the plugin version and, optionally, the Gradle version used by the project.
   *
   * @param pluginVersion the plugin version to update to.
   * @param gradleVersion the version of Gradle to update to (optional.)
   * @return the result of the update operation.
   */
  @NotNull
  public UpdateResult updatePluginVersion(@NotNull GradleVersion pluginVersion, @Nullable GradleVersion gradleVersion) {
    UpdateResult result = new UpdateResult();
    runWriteCommandAction(myProject, () -> updateAndroidPluginVersion(pluginVersion, gradleVersion, result));

    // Update Gradle version only if plugin is successful updated, to avoid leaving the project
    // in a inconsistent state.
    if (result.isPluginVersionUpdated() && gradleVersion != null) {
      runWriteCommandAction(myProject, () -> updateGradleWrapperVersion(gradleVersion, result));
    }
    return result;
  }

  @NotNull
  private static ThreeState isUpdatablePluginVersion(@NotNull GradleVersion pluginVersion, @NotNull ArtifactDependencyModel model) {
    String artifactId = model.name().forceString();
    String groupId = model.group().toString();
    if  (AndroidPluginGeneration.find(artifactId, groupId) == null) {
      return UNSURE;
    }

    String versionValue = model.version().toString();
    return (isEmpty(versionValue) || pluginVersion.compareTo(versionValue) != 0) ? YES : NO;
  }

  /**
   * Updates android plugin version.
   *
   * @param pluginVersion the plugin version to update to.
   * @param gradleVersion the Gradle version that the project will use (or null if it will not change)
   * @param result        result of the update operation.
   */
  private void updateAndroidPluginVersion(@NotNull GradleVersion pluginVersion, @Nullable GradleVersion gradleVersion, @NotNull UpdateResult result) {
    List<GradleBuildModel> modelsToUpdate = new ArrayList<>();

    // Refresh the file system to avoid reading stale cached virtual files.
    VirtualFileManager.getInstance().refreshWithoutFileWatcher(false /* synchronous */);

    BuildFileProcessor.getInstance().processRecursively(myProject, buildModel -> {
      DependenciesModel dependencies = buildModel.buildscript().dependencies();
      for (ArtifactDependencyModel dependency : dependencies.artifacts(CLASSPATH)) {
        ThreeState shouldUpdate = isUpdatablePluginVersion(pluginVersion, dependency);
        if (shouldUpdate == YES) {
          dependency.version().getResultModel().setValue(pluginVersion.toString());
          // Add Google Maven repository to buildscript (b/69977310)
          if (gradleVersion != null) {
            buildModel.buildscript().repositories().addGoogleMavenRepository(gradleVersion);
          }
          else {
            // Gradle version will *not* change, use project version
            buildModel.buildscript().repositories().addGoogleMavenRepository(myProject);
          }
          modelsToUpdate.add(buildModel);
        }
        else if (shouldUpdate == NO) {
          break;
        }
      }
      return true;
    }, true /* process composite builds */);

    boolean updateModels = !modelsToUpdate.isEmpty();
    if (updateModels) {
      try {
        for (GradleBuildModel buildModel : modelsToUpdate) {
          buildModel.applyChanges();
        }
        result.pluginVersionUpdated();
      }
      catch (Throwable e) {
        result.setPluginVersionUpdateError(e);
      }
    }
    else {
      result.setPluginVersionUpdateError(new RuntimeException("Failed to find gradle build models to update."));
    }
  }

  /**
   * Updates Gradle version in wrapper.
   *
   * @param gradleVersion the gradle version to update to.
   * @param result        the result of the update operation.
   */
  private void updateGradleWrapperVersion(@NotNull GradleVersion gradleVersion, @NotNull UpdateResult result) {
    String basePath = myProject.getBasePath();
    if (basePath == null) {
      return;
    }
    List<File> projectRootFolders = new ArrayList<>();
    projectRootFolders.add(new File(toSystemDependentPath(basePath)));
    projectRootFolders.addAll(getCompositeBuildFolderPaths(myProject));
    for (File rootFolder : projectRootFolders) {
      if (rootFolder != null) {
        try {
          File wrapperPropertiesFilePath = getDefaultPropertiesFilePath(rootFolder);
          GradleWrapper gradleWrapper = GradleWrapper.get(wrapperPropertiesFilePath);
          String current = gradleWrapper.getGradleVersion();
          GradleVersion parsedCurrent = null;
          if (current != null) {
            parsedCurrent = GradleVersion.tryParse(current);
          }
          if (parsedCurrent != null && !isSupportedGradleVersion(parsedCurrent)) {
            gradleWrapper.updateDistributionUrl(gradleVersion.toString());
            result.gradleVersionUpdated();
          }
        }
        catch (Throwable e) {
          result.setGradleVersionUpdateError(e);
        }
      }
    }
  }

  public static class UpdateResult {
    @Nullable private Throwable myPluginVersionUpdateError;
    @Nullable private Throwable myGradleVersionUpdateError;

    private boolean myPluginVersionUpdated;
    private boolean myGradleVersionUpdated;

    @VisibleForTesting
    public UpdateResult() {
    }

    @Nullable
    public Throwable getPluginVersionUpdateError() {
      return myPluginVersionUpdateError;
    }

    void setPluginVersionUpdateError(@NotNull Throwable error) {
      myPluginVersionUpdateError = error;
    }

    @Nullable
    public Throwable getGradleVersionUpdateError() {
      return myGradleVersionUpdateError;
    }

    void setGradleVersionUpdateError(@NotNull Throwable error) {
      myGradleVersionUpdateError = error;
    }

    public boolean isPluginVersionUpdated() {
      return myPluginVersionUpdated;
    }

    void pluginVersionUpdated() {
      myPluginVersionUpdated = true;
    }

    public boolean isGradleVersionUpdated() {
      return myGradleVersionUpdated;
    }

    void gradleVersionUpdated() {
      myGradleVersionUpdated = true;
    }

    public boolean versionUpdateSuccess() {
      return (myPluginVersionUpdated || myGradleVersionUpdated) && myPluginVersionUpdateError == null && myGradleVersionUpdateError == null;
    }
  }

  @VisibleForTesting
  static class TextSearch {
    @NotNull private final Project myProject;

    TextSearch(@NotNull Project project) {
      myProject = project;
    }

    void execute() {
      String msg = "Failed to update the version of the Android Gradle plugin.\n\n" +
                   "Please click 'OK' to perform a textual search and then update the build files manually.";
      ApplicationManager.getApplication().invokeLater(() -> {
        Messages.showErrorDialog(myProject, msg, "Unexpected Error");
        String textToFind = AndroidPluginGeneration.getGroupId() + ":" + AndroidPluginGeneration.ORIGINAL.getArtifactId();
        searchInBuildFiles(textToFind, myProject);
      });
    }
  }
}
