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

import com.android.tools.adtui.common.AdtPrimaryPanel;
import com.android.tools.adtui.workbench.*;
import com.android.tools.idea.AndroidPsiUtils;
import com.android.tools.idea.common.error.IssuePanelSplitter;
import com.android.tools.idea.common.model.NlLayoutType;
import com.android.tools.idea.common.model.NlModel;
import com.android.tools.idea.common.surface.DesignSurface;
import com.android.tools.idea.flags.StudioFlags;
import com.android.tools.idea.naveditor.property.NavPropertyPanelDefinition;
import com.android.tools.idea.naveditor.structure.StructurePanel;
import com.android.tools.idea.naveditor.surface.NavDesignSurface;
import com.android.tools.idea.startup.ClearResourceCacheAfterFirstBuild;
import com.android.tools.idea.uibuilder.mockup.editor.MockupToolDefinition;
import com.android.tools.idea.uibuilder.palette2.PaletteDefinition;
import com.android.tools.idea.uibuilder.property.NlPropertyPanelDefinition;
import com.android.tools.idea.uibuilder.property2.NelePropertiesPanelDefinition;
import com.android.tools.idea.uibuilder.structure.NlComponentTreeDefinition;
import com.android.tools.idea.uibuilder.surface.NlDesignSurface;
import com.android.tools.idea.util.SyncUtil;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.xml.XmlFile;
import com.intellij.ui.JBSplitter;
import com.intellij.ui.OnePixelSplitter;
import org.jetbrains.android.facet.AndroidFacet;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.TestOnly;

import javax.swing.*;
import java.awt.*;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

/**
 * Assembles a designer editor from various components
 */
public class NlEditorPanel extends JPanel implements Disposable {

  public static final String NELE_EDITOR = "NELE_EDITOR";
  public static final String NAV_EDITOR = "NAV_EDITOR";

  private static final String DESIGN_UNAVAILABLE_MESSAGE = "Design editor is unavailable until after a successful project sync";

  private final NlEditor myEditor;
  private final Project myProject;
  private final VirtualFile myFile;
  private final DesignSurface mySurface;
  private final JPanel myContentPanel;
  private final WorkBench<DesignSurface> myWorkBench;
  private JBSplitter mySplitter;
  private JPanel myAccessoryPanel;

  public NlEditorPanel(@NotNull NlEditor editor, @NotNull Project project, @NotNull VirtualFile file) {
    super(new BorderLayout());
    myEditor = editor;
    myProject = project;
    myFile = file;
    boolean isNavigation = getLayoutType() == NlLayoutType.NAV;
    myWorkBench = new WorkBench<>(project, isNavigation ? NAV_EDITOR : NELE_EDITOR, editor);
    myWorkBench.setOpaque(true);

    myContentPanel = new AdtPrimaryPanel(new BorderLayout());
    mySurface = createDesignSurface(editor, project, isNavigation);
    Disposer.register(this, mySurface);
    myContentPanel.add(createSurfaceToolbar(mySurface), BorderLayout.NORTH);

    myWorkBench.setLoadingText("Waiting for build to finish...");
    ClearResourceCacheAfterFirstBuild.getInstance(project).runWhenResourceCacheClean(this::initNeleModel, this::buildError);

    mySplitter = new IssuePanelSplitter(mySurface, myWorkBench);
    add(mySplitter);
    Disposer.register(editor, myWorkBench);
  }

  @NotNull
  private NlLayoutType getLayoutType() {
    return NlLayoutType.typeOf(getFile());
  }

  @NotNull
  private DesignSurface createDesignSurface(@NotNull NlEditor editor, @NotNull Project project, boolean isNavigation) {
    if (isNavigation) {
      return new NavDesignSurface(project, this, editor);
    }
    else {
      NlDesignSurface nlDesignSurface;
      nlDesignSurface = new NlDesignSurface(project, false, editor);
      nlDesignSurface.setCentered(true);
      myAccessoryPanel = nlDesignSurface.getAccessoryPanel();
      return nlDesignSurface;
    }
  }

  @NotNull
  private static JComponent createSurfaceToolbar(@NotNull DesignSurface surface) {
    return surface.getActionManager().createToolbar();
  }

  // Build was either cancelled or there was an error
  private void buildError() {
    myWorkBench.loadingStopped(DESIGN_UNAVAILABLE_MESSAGE);
  }

  /**
   * This is called by the constructor to set up the UI, and in the normal case shouldn't be called again. However,
   * if there's an error during setup that can be detected and fixed, this should be called again afterward to retry
   * setting up the UI.
   */
  public void initNeleModel() {
    SyncUtil.runWhenSmartAndSynced(myProject, this, result -> {
      if (result.isSuccessful()) {
        initNeleModelWhenSmart();
      }
      else {
        buildError();
        SyncUtil.listenUntilNextSync(myProject, this, ignore -> initNeleModel());
      }
    });
  }

  private void initNeleModelWhenSmart() {
    if (Disposer.isDisposed(myEditor)) {
      return;
    }

    NlModel model = ReadAction.compute(() -> {
      XmlFile file = getFile();

      AndroidFacet facet = AndroidFacet.getInstance(file);
      assert facet != null;
      return NlModel.create(myEditor, facet, myFile, mySurface.getConfigurationManager(facet));
    });
    CompletableFuture<?> complete = mySurface.goingToSetModel(model);
    complete.whenComplete((unused, exception) -> {
      if (exception == null) {
        SyncUtil.runWhenSmartAndSyncedOnEdt(myProject, this, result -> {
          if (result.isSuccessful()) {
            initNeleModelOnEventDispatchThread(model);
          }
          else {
            buildError();
            SyncUtil.listenUntilNextSync(myProject, this, ignore -> initNeleModel());
          }
        });
      }
      else {
        myWorkBench.loadingStopped("Failed to initialize editor");
        Logger.getInstance(NlEditorPanel.class).warn("Failed to initialize NlEditorPanel", exception);
      }
    });
  }

  private void initNeleModelOnEventDispatchThread(@NotNull NlModel model) {
    if (Disposer.isDisposed(model)) {
      return;
    }

    mySurface.setModel(model);

    if (myAccessoryPanel != null) {
      boolean verticalSplitter = StudioFlags.NELE_MOTION_HORIZONTAL.get();
      OnePixelSplitter splitter = new OnePixelSplitter(verticalSplitter, 1f, 0.5f, 1f);
      splitter.setHonorComponentsMinimumSize(true);
      splitter.setFirstComponent(mySurface);
      splitter.setSecondComponent(myAccessoryPanel);
      myContentPanel.add(splitter, BorderLayout.CENTER);
    }
    else {
      myContentPanel.add(mySurface, BorderLayout.CENTER);
    }

    List<ToolWindowDefinition<DesignSurface>> tools = new ArrayList<>(4);
    // TODO: factor out tool creation
    if (NlLayoutType.typeOf(model.getFile()) == NlLayoutType.NAV) {
      tools.add(new NavPropertyPanelDefinition(model.getFacet(), Side.RIGHT, Split.TOP, AutoHide.DOCKED));
      tools.add(new StructurePanel.StructurePanelDefinition());
    }
    else {
      tools.add(new PaletteDefinition(myProject, Side.LEFT, Split.TOP, AutoHide.DOCKED));
      if (StudioFlags.NELE_NEW_PROPERTY_PANEL.get()) {
        tools.add(new NelePropertiesPanelDefinition(model.getFacet(), Side.RIGHT, Split.TOP, AutoHide.DOCKED));
      }
      else {
        tools.add(new NlPropertyPanelDefinition(model.getFacet(), Side.RIGHT, Split.TOP, AutoHide.DOCKED));
      }
      tools.add(new NlComponentTreeDefinition(myProject, Side.LEFT, Split.BOTTOM, AutoHide.DOCKED));
      if (StudioFlags.NELE_MOCKUP_EDITOR.get()) {
        tools.add(new MockupToolDefinition(Side.RIGHT, Split.TOP, AutoHide.AUTO_HIDE));
      }
    }

    myWorkBench.init(myContentPanel, mySurface, tools);
  }

  @Nullable
  public JComponent getPreferredFocusedComponent() {
    return mySurface.getPreferredFocusedComponent();
  }

  public void activate() {
    mySurface.activate();
  }

  public void deactivate() {
    mySurface.deactivate();
  }

  @NotNull
  public DesignSurface getSurface() {
    return mySurface;
  }

  @NotNull
  private XmlFile getFile() {
    XmlFile file = (XmlFile)AndroidPsiUtils.getPsiFileSafely(myProject, myFile);
    assert file != null;
    return file;
  }

  @TestOnly
  public void setIssuePanelProportion(float proportion) {
    mySplitter.setProportion(proportion);
  }

  @Override
  public void dispose() {
  }

  public WorkBench<DesignSurface> getWorkBench() {
    return myWorkBench;
  }
}
