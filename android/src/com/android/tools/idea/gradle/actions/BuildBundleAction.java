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
package com.android.tools.idea.gradle.actions;

import com.android.tools.idea.flags.StudioFlags;
import com.android.tools.idea.gradle.project.build.invoker.GradleBuildInvoker;
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

import static com.android.tools.idea.gradle.util.GradleUtil.getGradlePath;

public class BuildBundleAction extends DumbAwareAction {
  private static final String ACTION_TEXT = "Build Bundle(s)";

  public BuildBundleAction() {
    super(ACTION_TEXT);
  }

  @Override
  public void update(AnActionEvent e) {
    boolean enabled = StudioFlags.RUNDEBUG_ANDROID_BUILD_BUNDLE_ENABLED.get() &&
                      AndroidStudioGradleAction.isAndroidGradleProject(e);
    e.getPresentation().setEnabledAndVisible(enabled);
  }

  @Override
  public void actionPerformed(AnActionEvent e) {
    Project project = e.getProject();
    if (AndroidStudioGradleAction.isAndroidGradleProject(e)) {
      List<Module> appModules = DynamicAppUtils.getModulesSupportingBundleTask(project);
      if (!appModules.isEmpty()) {
        GradleBuildInvoker gradleBuildInvoker = GradleBuildInvoker.getInstance(project);
        gradleBuildInvoker.add(new GoToBundleLocationTask(project, ACTION_TEXT, appModules));
        Module[] modulesToBuild = appModules.toArray(Module.EMPTY_ARRAY);
        gradleBuildInvoker.bundle(modulesToBuild, Collections.emptyList(), new OutputBuildAction(getModuleGradlePaths(appModules)));
      } else {
        DynamicAppUtils.promptUserForGradleUpdate(project);
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
