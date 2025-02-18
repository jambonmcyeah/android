/*
 * Copyright (C) 2014 The Android Open Source Project
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
package com.android.tools.idea.actions;

import com.android.tools.idea.editors.theme.ThemeEditorUtils;
import com.android.tools.idea.flags.StudioFlags;
import com.intellij.openapi.actionSystem.AnAction;
import com.intellij.openapi.actionSystem.AnActionEvent;
import com.intellij.openapi.project.Project;
import icons.AndroidIcons;

public class AndroidShowThemeEditor extends AnAction {
  public AndroidShowThemeEditor() {
    super("Theme Editor", null, AndroidIcons.Themes);
  }

  @Override
  public void update(final AnActionEvent e) {
    if (!StudioFlags.THEME_EDITOR_ENABLED.get()) {
      e.getPresentation().setVisible(false);
      return;
    }
    Project project = e.getProject();
    e.getPresentation().setEnabled(project != null && ThemeEditorUtils.findAndroidModules(project).findAny().isPresent());
  }

  @Override
  public void actionPerformed(final AnActionEvent e) {
    Project project = e.getProject();
    if (project == null) {
      return;
    }

    ThemeEditorUtils.openThemeEditor(project);
  }
}
