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
package com.android.tools.idea.gradle.structure.configurables.android.dependencies.project;

import com.android.tools.idea.gradle.structure.configurables.PsContext;
import com.android.tools.idea.gradle.structure.configurables.android.dependencies.PsAllModulesFakeModule;
import com.android.tools.idea.gradle.structure.configurables.android.modules.AbstractModuleConfigurable;
import com.android.tools.idea.gradle.structure.model.PsModule;
import com.intellij.icons.AllIcons;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import java.util.List;

public class ProjectDependenciesConfigurable extends AbstractModuleConfigurable<PsAllModulesFakeModule, MainPanel> {
  @NotNull private final List<PsModule> myExtraModules;

  public ProjectDependenciesConfigurable(@NotNull PsAllModulesFakeModule module,
                                         @NotNull PsContext context,
                                         @NotNull List<PsModule> extraModules) {
    super(context, module);
    myExtraModules = extraModules;
    setDisplayName("<All Modules>");
  }

  @Override
  public String getBannerSlogan() {
    return getDisplayName();
  }

  @Override
  public MainPanel createPanel() {
    return new MainPanel(getModule(), getContext(), myExtraModules);
  }

  @Override
  @NotNull
  public String getId() {
    return "all.modules.dependencies";
  }

  @Override
  @NotNull
  public Icon getIcon(boolean expanded) {
    return AllIcons.Nodes.ModuleGroup;
  }
}
