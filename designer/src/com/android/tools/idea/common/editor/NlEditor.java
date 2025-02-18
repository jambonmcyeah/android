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
package com.android.tools.idea.common.editor;

import com.android.tools.idea.common.lint.BackgroundEditorHighlighter;
import com.intellij.ide.structureView.StructureViewBuilder;
import com.intellij.openapi.fileEditor.FileEditor;
import com.intellij.openapi.fileEditor.FileEditorLocation;
import com.intellij.openapi.fileEditor.FileEditorManager;
import com.intellij.openapi.fileEditor.FileEditorState;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.util.UserDataHolderBase;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.util.ArrayUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.beans.PropertyChangeListener;

public class NlEditor extends UserDataHolderBase implements FileEditor {
  private final Project myProject;
  private final VirtualFile myFile;

  private NlEditorPanel myEditorPanel;
  private BackgroundEditorHighlighter myBackgroundHighlighter;

  public NlEditor(@NotNull VirtualFile file, Project project) {
    myProject = project;
    myFile = file;
  }

  @NotNull
  @Override
  public NlEditorPanel getComponent() {
    if (myEditorPanel == null) {
      myEditorPanel = new NlEditorPanel(this, myProject, myFile);
    }
    return myEditorPanel;
  }

  @Nullable
  @Override
  public JComponent getPreferredFocusedComponent() {
    return getComponent().getPreferredFocusedComponent();
  }

  @NotNull
  @Override
  public String getName() {
    return "Design";
  }

  @Override
  public void setState(@NotNull FileEditorState state) {
  }

  @Override
  public void dispose() {
    Disposer.dispose(getComponent());
  }

  @Override
  public void selectNotify() {
    // select/deselectNotify will be called when the user selects (clicks) or opens a new editor. However, in some cases, the editor
    // might be deselected but still visible. We first check whether we should pay attention to the select/deselect so we only do something
    // if we are visible
    if (ArrayUtil.contains(this, FileEditorManager.getInstance(myProject).getSelectedEditors())) {
      getComponent().activate();
    }
  }

  @Override
  public void deselectNotify() {
    // If we are still visible but the user deselected us, do not deactivate the model since we still need to receive updates
    if (!ArrayUtil.contains(this, FileEditorManager.getInstance(myProject).getSelectedEditors())) {
      getComponent().deactivate();
    }
  }

  @Override
  public boolean isValid() {
    return myFile.isValid();
  }

  @Override
  public boolean isModified() {
    return false;
  }

  @Override
  public void addPropertyChangeListener(@NotNull PropertyChangeListener listener) {
  }

  @Override
  public void removePropertyChangeListener(@NotNull PropertyChangeListener listener) {
  }

  @Nullable
  @Override
  public BackgroundEditorHighlighter getBackgroundHighlighter() {
    // The designer should display components that have problems detected by inspections. Ideally, we'd just get the result
    // of all the inspections on the XML file. However, it doesn't look like there is an API to obtain this for a file
    // (there are test APIs). So we add a single highlighter which uses lint.
    if (myBackgroundHighlighter == null) {
      myBackgroundHighlighter = new BackgroundEditorHighlighter(myEditorPanel);
    }
    return myBackgroundHighlighter;
  }

  @Nullable
  @Override
  public FileEditorLocation getCurrentLocation() {
    return null;
  }

  @Nullable
  @Override
  public StructureViewBuilder getStructureViewBuilder() {
    return null;
  }

  @Nullable
  @Override
  public VirtualFile getFile() {
    return myFile;
  }
}
