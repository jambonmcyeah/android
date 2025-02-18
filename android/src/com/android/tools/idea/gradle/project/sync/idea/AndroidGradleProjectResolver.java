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
package com.android.tools.idea.gradle.project.sync.idea;

import com.android.builder.model.*;
import com.android.builder.model.level2.GlobalLibraryMap;
import com.android.ide.common.gradle.model.IdeNativeAndroidProject;
import com.android.ide.common.gradle.model.IdeNativeAndroidProjectImpl;
import com.android.ide.common.gradle.model.level2.IdeDependenciesFactory;
import com.android.ide.common.repository.GradleVersion;
import com.android.repository.Revision;
import com.android.tools.analytics.UsageTracker;
import com.android.tools.idea.IdeInfo;
import com.android.tools.idea.gradle.plugin.AndroidPluginGeneration;
import com.android.tools.idea.gradle.project.model.*;
import com.android.tools.idea.gradle.project.sync.common.CommandLineArgs;
import com.android.tools.idea.gradle.project.sync.common.VariantSelector;
import com.android.tools.idea.gradle.project.sync.idea.data.model.ImportedModule;
import com.android.tools.idea.gradle.project.sync.idea.data.model.ProjectCleanupModel;
import com.android.tools.idea.gradle.util.AndroidGradleSettings;
import com.android.tools.idea.gradle.util.LocalProperties;
import com.android.tools.idea.sdk.IdeSdks;
import com.google.common.annotations.VisibleForTesting;
import com.google.wireless.android.sdk.stats.AndroidStudioEvent;
import com.google.wireless.android.sdk.stats.AndroidStudioEvent.GradleSyncFailure;
import com.intellij.execution.configurations.SimpleJavaParameters;
import com.intellij.openapi.externalSystem.model.DataNode;
import com.intellij.openapi.externalSystem.model.ExternalSystemException;
import com.intellij.openapi.externalSystem.model.ProjectKeys;
import com.intellij.openapi.externalSystem.model.ProjectSystemId;
import com.intellij.openapi.externalSystem.model.project.ModuleData;
import com.intellij.openapi.externalSystem.model.project.ProjectData;
import com.intellij.openapi.externalSystem.model.task.TaskData;
import com.intellij.openapi.externalSystem.util.ExternalSystemConstants;
import com.intellij.openapi.externalSystem.util.Order;
import com.intellij.openapi.module.StdModuleTypes;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.util.Pair;
import com.intellij.serviceContainer.NonInjectable;
import com.intellij.util.PathsList;
import org.gradle.tooling.model.GradleProject;
import org.gradle.tooling.model.ProjectModel;
import org.gradle.tooling.model.build.BuildEnvironment;
import org.gradle.tooling.model.gradle.GradleScript;
import org.gradle.tooling.model.idea.IdeaModule;
import org.gradle.tooling.model.idea.IdeaProject;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.plugins.gradle.model.Build;
import org.jetbrains.plugins.gradle.model.BuildScriptClasspathModel;
import org.jetbrains.plugins.gradle.model.ExternalProject;
import org.jetbrains.plugins.gradle.service.project.AbstractProjectResolverExtension;
import org.jetbrains.plugins.gradle.util.GradleConstants;

import java.io.File;
import java.io.IOException;
import java.util.*;

import static com.android.SdkConstants.FN_SETTINGS_GRADLE;
import static com.android.tools.idea.gradle.project.sync.SimulatedSyncErrors.simulateRegisteredSyncError;
import static com.android.tools.idea.gradle.project.sync.errors.UnsupportedModelVersionErrorHandler.READ_MIGRATION_GUIDE_MSG;
import static com.android.tools.idea.gradle.project.sync.errors.UnsupportedModelVersionErrorHandler.UNSUPPORTED_MODEL_VERSION_ERROR_PREFIX;
import static com.android.tools.idea.gradle.project.sync.idea.GradleModelVersionCheck.getModelVersion;
import static com.android.tools.idea.gradle.project.sync.idea.GradleModelVersionCheck.isSupportedVersion;
import static com.android.tools.idea.gradle.project.sync.idea.data.service.AndroidProjectKeys.*;
import static com.android.tools.idea.gradle.util.AndroidGradleSettings.ANDROID_HOME_JVM_ARG;
import static com.android.tools.idea.gradle.util.GradleUtil.GRADLE_SYSTEM_ID;
import static com.android.tools.idea.io.FilePaths.toSystemDependentPath;
import static com.google.wireless.android.sdk.stats.AndroidStudioEvent.EventCategory.GRADLE_SYNC;
import static com.google.wireless.android.sdk.stats.AndroidStudioEvent.EventKind.GRADLE_SYNC_FAILURE;
import static com.google.wireless.android.sdk.stats.AndroidStudioEvent.GradleSyncFailure.UNSUPPORTED_ANDROID_MODEL_VERSION;
import static com.intellij.openapi.externalSystem.util.ExternalSystemApiUtil.isInProcessMode;
import static com.intellij.openapi.util.text.StringUtil.isEmpty;
import static com.intellij.util.ExceptionUtil.getRootCause;
import static com.intellij.util.PathUtil.getJarPathForClass;
import static java.util.Collections.emptyList;
import static org.jetbrains.plugins.gradle.service.project.GradleProjectResolverUtil.getModuleConfigPath;

/**
 * Imports Android-Gradle projects into IDEA.
 */
@Order(ExternalSystemConstants.UNORDERED)
public class AndroidGradleProjectResolver extends AbstractProjectResolverExtension {
  private static final Key<Boolean> IS_ANDROID_PROJECT_KEY = Key.create("IS_ANDROID_PROJECT_KEY");

  @NotNull private final CommandLineArgs myCommandLineArgs;
  @NotNull private final ProjectImportErrorHandler myErrorHandler;
  @NotNull private final ProjectFinder myProjectFinder;
  @NotNull private final VariantSelector myVariantSelector;
  @NotNull private final IdeNativeAndroidProject.Factory myNativeAndroidProjectFactory;
  @NotNull private final IdeaJavaModuleModelFactory myIdeaJavaModuleModelFactory;
  @NotNull private final IdeDependenciesFactory myDependenciesFactory;

  @SuppressWarnings("unused")
  // This constructor is used by the IDE. This class is an extension point implementation, registered in plugin.xml.
  public AndroidGradleProjectResolver() {
    this(new CommandLineArgs(false /* do not apply Java library plugin */), new ProjectImportErrorHandler(), new ProjectFinder(),
         new VariantSelector(), new IdeNativeAndroidProjectImpl.FactoryImpl(), new IdeaJavaModuleModelFactory(),
         new IdeDependenciesFactory());
  }

  @VisibleForTesting
  @NonInjectable
  AndroidGradleProjectResolver(@NotNull CommandLineArgs commandLineArgs,
                               @NotNull ProjectImportErrorHandler errorHandler,
                               @NotNull ProjectFinder projectFinder,
                               @NotNull VariantSelector variantSelector,
                               @NotNull IdeNativeAndroidProject.Factory nativeAndroidProjectFactory,
                               @NotNull IdeaJavaModuleModelFactory ideaJavaModuleModelFactory,
                               @NotNull IdeDependenciesFactory dependenciesFactory) {
    myCommandLineArgs = commandLineArgs;
    myErrorHandler = errorHandler;
    myProjectFinder = projectFinder;
    myVariantSelector = variantSelector;
    myNativeAndroidProjectFactory = nativeAndroidProjectFactory;
    myIdeaJavaModuleModelFactory = ideaJavaModuleModelFactory;
    myDependenciesFactory = dependenciesFactory;
  }

  @Override
  @NotNull
  public DataNode<ModuleData> createModule(@NotNull IdeaModule gradleModule, @NotNull DataNode<ProjectData> projectDataNode) {
    AndroidProject androidProject = resolverCtx.getExtraProject(gradleModule, AndroidProject.class);
    if (androidProject != null && !isSupportedVersion(androidProject)) {
      AndroidStudioEvent.Builder event = AndroidStudioEvent.newBuilder();
      // @formatter:off
      event.setCategory(GRADLE_SYNC)
           .setKind(GRADLE_SYNC_FAILURE)
           .setGradleSyncFailure(UNSUPPORTED_ANDROID_MODEL_VERSION)
           .setGradleVersion(androidProject.getModelVersion());
      // @formatter:on

      UsageTracker.log(event);

      String msg = getUnsupportedModelVersionErrorMsg(getModelVersion(androidProject));
      throw new IllegalStateException(msg);
    }
    if (isAndroidGradleProject()) {
      return doCreateModule(gradleModule, projectDataNode);
    }
    else {
      return nextResolver.createModule(gradleModule, projectDataNode);
    }
  }

  @NotNull
  private DataNode<ModuleData> doCreateModule(@NotNull IdeaModule gradleModule, @NotNull DataNode<ProjectData> projectDataNode) {
    String moduleName = gradleModule.getName();
    if (moduleName == null) {
      throw new IllegalStateException("Module with undefined name detected: " + gradleModule);
    }

    String projectPath = projectDataNode.getData().getLinkedExternalProjectPath();
    String moduleConfigPath = getModuleConfigPath(resolverCtx, gradleModule, projectPath);

    String gradlePath = gradleModule.getGradleProject().getPath();
    String moduleId = isEmpty(gradlePath) || ":".equals(gradlePath) ? moduleName : gradlePath;
    ProjectSystemId owner = GradleConstants.SYSTEM_ID;
    String typeId = StdModuleTypes.JAVA.getId();

    ModuleData moduleData = new ModuleData(moduleId, owner, typeId, moduleName, moduleConfigPath, moduleConfigPath);

    ExternalProject externalProject = resolverCtx.getExtraProject(gradleModule, ExternalProject.class);
    if (externalProject != null) {
      moduleData.setDescription(externalProject.getDescription());
    }
    return projectDataNode.createChild(ProjectKeys.MODULE, moduleData);
  }

  @Override
  public void populateModuleContentRoots(@NotNull IdeaModule gradleModule, @NotNull DataNode<ModuleData> ideModule) {
    if (!IdeInfo.getInstance().isAndroidStudio() && !isAndroidGradleProject()) {
      nextResolver.populateModuleContentRoots(gradleModule, ideModule);
      return;
    }

    ImportedModule importedModule = new ImportedModule(gradleModule);
    ideModule.createChild(IMPORTED_MODULE, importedModule);

    // do not derive module root dir based on *.iml file location
    File moduleRootDirPath = toSystemDependentPath(ideModule.getData().getLinkedExternalProjectPath());

    AndroidProject androidProject = resolverCtx.getExtraProject(gradleModule, AndroidProject.class);

    boolean androidProjectWithoutVariants = false;
    // This stores the sync issues that should be attached to a Java module if we have a AndroidProject without variants.
    Collection<SyncIssue> syncIssues = new ArrayList<>();
    String moduleName = gradleModule.getName();

    if (androidProject != null) {
      Variant selectedVariant = myVariantSelector.findVariantToSelect(androidProject);
      if (selectedVariant == null) {
        // If an Android project does not have variants, it would be impossible to build. This is a possible but invalid use case.
        // For now we are going to treat this case as a Java library module, because everywhere in the IDE (e.g. run configurations,
        // editors, test support, variants tool window, project building, etc.) we have the assumption that there is at least one variant
        // per Android project, and changing that in the code base is too risky, for very little benefit.
        // See https://code.google.com/p/android/issues/detail?id=170722
        androidProjectWithoutVariants = true;
        syncIssues = androidProject.getSyncIssues();
      }
      else {
        String variantName = selectedVariant.getName();
        AndroidModuleModel model =
          new AndroidModuleModel(moduleName, moduleRootDirPath, androidProject, variantName, myDependenciesFactory);
        ideModule.createChild(ANDROID_MODEL, model);
      }
    }

    NativeAndroidProject nativeAndroidProject = resolverCtx.getExtraProject(gradleModule, NativeAndroidProject.class);
    if (nativeAndroidProject != null) {
      IdeNativeAndroidProject copy = myNativeAndroidProjectFactory.create(nativeAndroidProject);
      NdkModuleModel ndkModuleModel = new NdkModuleModel(moduleName, moduleRootDirPath, copy, emptyList());
      ideModule.createChild(NDK_MODEL, ndkModuleModel);
    }

    File gradleSettingsFile = new File(moduleRootDirPath, FN_SETTINGS_GRADLE);
    if (gradleSettingsFile.isFile() && androidProject == null && nativeAndroidProject == null &&
        // if the module has artifacts, it is a Java library module.
        // https://code.google.com/p/android/issues/detail?id=226802
        !hasArtifacts(gradleModule)) {
      // This is just a root folder for a group of Gradle projects. We don't set an IdeaGradleProject so the JPS builder won't try to
      // compile it using Gradle. We still need to create the module to display files inside it.
      createJavaProject(gradleModule, ideModule, syncIssues /* empty list */, false);
      return;
    }

    BuildScriptClasspathModel buildScriptModel = resolverCtx.getExtraProject(BuildScriptClasspathModel.class);
    String gradleVersion = buildScriptModel != null ? buildScriptModel.getGradleVersion() : null;

    GradleProject gradleProject = gradleModule.getGradleProject();
    GradleScript buildScript = null;
    try {
      buildScript = gradleProject.getBuildScript();
    }
    catch (UnsupportedOperationException ignore) {
    }
    File buildFilePath = buildScript != null ? buildScript.getSourceFile() : null;
    GradleModuleModel gradleModuleModel = new GradleModuleModel(moduleName, gradleProject, emptyList(), buildFilePath, gradleVersion);
    ideModule.createChild(GRADLE_MODULE_MODEL, gradleModuleModel);

    if (nativeAndroidProject == null && (androidProject == null || androidProjectWithoutVariants)) {
      // This is a Java lib module.
      createJavaProject(gradleModule, ideModule, syncIssues, androidProjectWithoutVariants);
    }
  }

  private boolean hasArtifacts(@NotNull IdeaModule gradleModule) {
    ExternalProject externalProject = resolverCtx.getExtraProject(gradleModule, ExternalProject.class);
    return externalProject != null && !externalProject.getArtifacts().isEmpty();
  }

  private void createJavaProject(@NotNull IdeaModule gradleModule,
                                 @NotNull DataNode<ModuleData> ideModule,
                                 @NotNull Collection<SyncIssue> syncIssues,
                                 boolean androidProjectWithoutVariants) {
    ExternalProject externalProject = resolverCtx.getExtraProject(gradleModule, ExternalProject.class);
    JavaModuleModel javaModuleModel =
      myIdeaJavaModuleModelFactory.create(gradleModule, externalProject, syncIssues, androidProjectWithoutVariants);
    ideModule.createChild(JAVA_MODULE_MODEL, javaModuleModel);
  }

  @Override
  public void populateModuleCompileOutputSettings(@NotNull IdeaModule gradleModule, @NotNull DataNode<ModuleData> ideModule) {
    if (!isAndroidGradleProject()) {
      nextResolver.populateModuleCompileOutputSettings(gradleModule, ideModule);
    }
  }

  @Override
  public void populateModuleDependencies(@NotNull IdeaModule gradleModule,
                                         @NotNull DataNode<ModuleData> ideModule,
                                         @NotNull DataNode<ProjectData> ideProject) {
    if (!isAndroidGradleProject()) {
      // For plain Java projects (non-Gradle) we let the framework populate dependencies
      nextResolver.populateModuleDependencies(gradleModule, ideModule, ideProject);
    }
  }

  // Indicates it is an "Android" project if at least one module has an AndroidProject.
  private boolean isAndroidGradleProject() {
    Boolean isAndroidGradleProject = resolverCtx.getUserData(IS_ANDROID_PROJECT_KEY);
    if (isAndroidGradleProject != null) {
      return isAndroidGradleProject;
    }
    isAndroidGradleProject = resolverCtx.hasModulesWithModel(AndroidProject.class) ||
                             resolverCtx.hasModulesWithModel(NativeAndroidProject.class);
    return resolverCtx.putUserDataIfAbsent(IS_ANDROID_PROJECT_KEY, isAndroidGradleProject);
  }

  @Override
  @NotNull
  public Collection<TaskData> populateModuleTasks(@NotNull IdeaModule gradleModule,
                                                  @NotNull DataNode<ModuleData> ideModule,
                                                  @NotNull DataNode<ProjectData> ideProject)
    throws IllegalArgumentException, IllegalStateException {
    // Do not drop gradle tasks for included projects (IDEA-193964)
    // IntelliJ gradle integration heavily depends on the gradle tasks information.
    // E.g. the build/run delegation feature and gradle test runner uses 'available' gradle tasks to run tests, applications and build sources, archives etc.
    // All this stuff also should work for gradle project included into the gradle composite build.
    if(IdeInfo.getInstance().isAndroidStudio()) {
      // Gradle doesn't support running tasks for included projects. Don't create task node if this module belongs to an included projects.
      IdeaProject mainIdeaProject = resolverCtx.getModels().getModel(IdeaProject.class);
      assert mainIdeaProject != null;
      if (!mainIdeaProject.equals(gradleModule.getProject())) {
        return emptyList();
      }
    }
    return nextResolver.populateModuleTasks(gradleModule, ideModule, ideProject);
  }

  @Override
  public void populateProjectExtraModels(@NotNull IdeaProject gradleProject, @NotNull DataNode<ProjectData> projectDataNode) {
    populateModuleBuildDirs(gradleProject);
    populateGlobalLibraryMap();
    if (isAndroidGradleProject()) {
      projectDataNode.createChild(PROJECT_CLEANUP_MODEL, ProjectCleanupModel.getInstance());
    }
    super.populateProjectExtraModels(gradleProject, projectDataNode);
  }

  /**
   * Set map from project path to build directory for all modules.
   * It will be used to check if a {@link AndroidLibrary} is sub-module that wraps local aar.
   */
  private void populateModuleBuildDirs(@NotNull IdeaProject rootIdeaProject) {
    // Set root build id.
    for (IdeaModule ideaModule : rootIdeaProject.getChildren()) {
      GradleProject gradleProject = ideaModule.getGradleProject();
      if (gradleProject != null) {
        String rootBuildId = gradleProject.getProjectIdentifier().getBuildIdentifier().getRootDir().getPath();
        myDependenciesFactory.setRootBuildId(rootBuildId);
        break;
      }
    }

    // Set build folder for root and included projects.
    List<IdeaProject> ideaProjects = new ArrayList<>();
    ideaProjects.add(rootIdeaProject);
    List<Build> includedBuilds = resolverCtx.getModels().getIncludedBuilds();
    for (Build includedBuild : includedBuilds) {
      IdeaProject ideaProject = resolverCtx.getModels().getModel(includedBuild, IdeaProject.class);
      assert ideaProject != null;
      ideaProjects.add(ideaProject);
    }

    for (IdeaProject ideaProject : ideaProjects) {
      for (IdeaModule ideaModule : ideaProject.getChildren()) {
        GradleProject gradleProject = ideaModule.getGradleProject();
        if (gradleProject != null) {
          try {
            String buildId = gradleProject.getProjectIdentifier().getBuildIdentifier().getRootDir().getPath();
            myDependenciesFactory.findAndAddBuildFolderPath(buildId, gradleProject.getPath(), gradleProject.getBuildDirectory());
          }
          catch (UnsupportedOperationException exception) {
            // getBuildDirectory is not available for Gradle older than 2.0.
            // For older versions of gradle, there's no way to get build directory.
          }
        }
      }
    }
  }

  /**
   * Find and set global library map.
   */
  private void populateGlobalLibraryMap() {
    List<GlobalLibraryMap> globalLibraryMaps = new ArrayList<>();

    // Request GlobalLibraryMap for root and included projects.
    Build mainBuild = resolverCtx.getModels().getMainBuild();
    List<Build> includedBuilds = resolverCtx.getModels().getIncludedBuilds();
    List<Build> builds = new ArrayList<>(includedBuilds.size() + 1);
    builds.add(mainBuild);
    builds.addAll(includedBuilds);

    for (Build build : builds) {
      GlobalLibraryMap mapOfCurrentBuild = null;
      // Since GlobalLibraryMap is requested on each module, we need to find the map that was
      // requested at the last, which is the one that contains the most of items.
      for (ProjectModel projectModel : build.getProjects()) {
        GlobalLibraryMap moduleMap = resolverCtx.getModels().getModel(projectModel, GlobalLibraryMap.class);
        if (mapOfCurrentBuild == null || (moduleMap != null && moduleMap.getLibraries().size() > mapOfCurrentBuild.getLibraries().size())) {
          mapOfCurrentBuild = moduleMap;
        }
      }
      if (mapOfCurrentBuild != null) {
        globalLibraryMaps.add(mapOfCurrentBuild);
      }
    }
    myDependenciesFactory.setUpGlobalLibraryMap(globalLibraryMaps);
  }

  @Override
  @NotNull
  public Set<Class> getExtraProjectModelClasses() {
    // Use LinkedHashSet to maintain insertion order.
    // GlobalLibraryMap should be requested after AndroidProject.
    Set<Class> modelClasses = new LinkedHashSet<>();
    modelClasses.add(AndroidProject.class);
    modelClasses.add(NativeAndroidProject.class);
    modelClasses.add(GlobalLibraryMap.class);
    return modelClasses;
  }

  @Override
  public void preImportCheck() {
    simulateRegisteredSyncError();
  }

  @Override
  @NotNull
  public List<Pair<String, String>> getExtraJvmArgs() {
    if (isInProcessMode(GRADLE_SYSTEM_ID)) {
      List<Pair<String, String>> args = new ArrayList<>();

      if (!IdeInfo.getInstance().isAndroidStudio()) {
        LocalProperties localProperties = getLocalProperties();
        if (localProperties.getAndroidSdkPath() == null) {
          File androidHomePath = IdeSdks.getInstance().getAndroidSdkPath();
          // In Android Studio, the Android SDK home path will never be null. It may be null when running in IDEA.
          if (androidHomePath != null) {
            args.add(Pair.create(ANDROID_HOME_JVM_ARG, androidHomePath.getPath()));
          }
        }
      }
      return args;
    }
    return emptyList();
  }

  @NotNull
  private LocalProperties getLocalProperties() {
    File projectDir = toSystemDependentPath(resolverCtx.getProjectPath());
    try {
      return new LocalProperties(projectDir);
    }
    catch (IOException e) {
      String msg = String.format("Unable to read local.properties file in project '%1$s'", projectDir.getPath());
      throw new ExternalSystemException(msg, e);
    }
  }

  @Override
  @NotNull
  public List<String> getExtraCommandLineArgs() {
    Project project = myProjectFinder.findProject(resolverCtx);
    return myCommandLineArgs.get(project);
  }

  @NotNull
  @Override
  public ExternalSystemException getUserFriendlyError(@Nullable BuildEnvironment buildEnvironment,
                                                      @NotNull Throwable error,
                                                      @NotNull String projectPath,
                                                      @Nullable String buildFilePath) {
    String msg = error.getMessage();
    if (msg != null && !msg.contains(UNSUPPORTED_MODEL_VERSION_ERROR_PREFIX)) {
      Throwable rootCause = getRootCause(error);
      if (rootCause instanceof ClassNotFoundException) {
        msg = rootCause.getMessage();
        // Project is using an old version of Gradle (and most likely an old version of the plug-in.)
        if (isUsingUnsupportedGradleVersion(msg)) {
          AndroidStudioEvent.Builder event = AndroidStudioEvent.newBuilder();
          // @formatter:off
          event.setCategory(GRADLE_SYNC)
               .setKind(GRADLE_SYNC_FAILURE)
               .setGradleSyncFailure(GradleSyncFailure.UNSUPPORTED_GRADLE_VERSION);
          // @formatter:on;
          UsageTracker.log(event);

          return new ExternalSystemException("The project is using an unsupported version of Gradle.");
        }
      }
    }
    ExternalSystemException userFriendlyError = myErrorHandler.getUserFriendlyError(buildEnvironment, error, projectPath, buildFilePath);
    assert userFriendlyError != null;
    return userFriendlyError;
  }

  private static boolean isUsingUnsupportedGradleVersion(@Nullable String errorMessage) {
    return "org.gradle.api.artifacts.result.ResolvedComponentResult".equals(errorMessage) ||
           "org.gradle.api.artifacts.result.ResolvedModuleVersionResult".equals(errorMessage);
  }

  @NotNull
  private static String getUnsupportedModelVersionErrorMsg(@Nullable GradleVersion modelVersion) {
    StringBuilder builder = new StringBuilder();
    builder.append(UNSUPPORTED_MODEL_VERSION_ERROR_PREFIX);
    String recommendedVersion = String.format("The recommended version is %1$s.", AndroidPluginGeneration.ORIGINAL.getLatestKnownVersion());
    if (modelVersion != null) {
      builder.append(String.format(" (%1$s).", modelVersion.toString())).append(" ").append(recommendedVersion);
      if (modelVersion.getMajor() == 0 && modelVersion.getMinor() <= 8) {
        // @formatter:off
        builder.append("\n\nStarting with version 0.9.0 incompatible changes were introduced in the build language.\n")
               .append(READ_MIGRATION_GUIDE_MSG)
               .append(" to learn how to update your project.");
        // @formatter:on
      }
    }
    else {
      builder.append(". ").append(recommendedVersion);
    }
    return builder.toString();
  }

  @Override
  public void enhanceRemoteProcessing(@NotNull SimpleJavaParameters parameters) {
    PathsList classPath = parameters.getClassPath();
    classPath.add(getJarPathForClass(getClass()));
    classPath.add(getJarPathForClass(Revision.class));
    classPath.add(getJarPathForClass(AndroidGradleSettings.class));
    classPath.add(getJarPathForClass(AndroidProject.class));
  }
}
