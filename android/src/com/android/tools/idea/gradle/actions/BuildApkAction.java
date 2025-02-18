/*
 * Copyright (C) 2015 The Android Open Source Project
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
package com.android.tools.idea.gradle.actions;

import com.android.tools.idea.gradle.project.GradleProjectInfo;
import com.android.tools.idea.gradle.project.ProjectStructure;
import com.android.tools.idea.gradle.project.build.invoker.GradleBuildInvoker;
import com.android.tools.idea.gradle.project.build.invoker.TestCompileType;
import com.android.tools.idea.gradle.run.OutputBuildAction;
import com.android.tools.idea.gradle.util.DynamicAppUtils;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.project.DumbAwareAction;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import static com.android.tools.idea.gradle.util.GradleUtil.getGradlePath;

public class BuildApkAction extends DumbAwareAction {
  private static final String ACTION_TEXT = "Build APK(s)";

  public BuildApkAction() {
    super(ACTION_TEXT);
  }

  @Override
  public void update(@NotNull AnActionEvent e) {
    e.getPresentation().setEnabledAndVisible(AndroidStudioGradleAction.isAndroidGradleProject(e));
  }

  @Override
  public void actionPerformed(@NotNull AnActionEvent e) {
    Project project = e.getProject();
    if (project != null && GradleProjectInfo.getInstance(project).isBuildWithGradle()) {
      List<Module> appModules = ProjectStructure.getInstance(project).getAppModules().stream()
        .flatMap(module -> DynamicAppUtils.getModulesToBuild(module).stream())
        .collect(Collectors.toList());
      if (!appModules.isEmpty()) {
        GradleBuildInvoker gradleBuildInvoker = GradleBuildInvoker.getInstance(project);
        gradleBuildInvoker.add(new GoToApkLocationTask(appModules, ACTION_TEXT));
        Module[] modulesToBuild = appModules.toArray(Module.EMPTY_ARRAY);
        gradleBuildInvoker.assemble(modulesToBuild, TestCompileType.ALL, Collections.emptyList(),
                                    new OutputBuildAction(getModuleGradlePaths(appModules)));
      }
    }
  }

  @NotNull
  private static List<String> getModuleGradlePaths(@NotNull List<Module> modules) {
    List<String> gradlePaths = new ArrayList<>();
    for (Module module : modules) {
      String gradlePath = getGradlePath(module);
      if (gradlePath != null) {
        gradlePaths.add(gradlePath);
      }
    }
    return gradlePaths;
  }
}
