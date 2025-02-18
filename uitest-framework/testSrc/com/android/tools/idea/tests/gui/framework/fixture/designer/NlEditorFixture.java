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
package com.android.tools.idea.tests.gui.framework.fixture.designer;

import com.android.tools.adtui.workbench.WorkBench;
import com.android.tools.idea.common.editor.NlEditor;
import com.android.tools.idea.common.editor.NlEditorPanel;
import com.android.tools.idea.common.model.AndroidDpCoordinate;
import com.android.tools.idea.common.model.Coordinates;
import com.android.tools.idea.common.model.NlComponent;
import com.android.tools.idea.common.surface.DesignSurface;
import com.android.tools.idea.common.surface.SceneView;
import com.android.tools.idea.naveditor.surface.NavDesignSurface;
import com.android.tools.idea.tests.gui.framework.GuiTests;
import com.android.tools.idea.tests.gui.framework.fixture.ComponentFixture;
import com.android.tools.idea.tests.gui.framework.fixture.CreateResourceDirectoryDialogFixture;
import com.android.tools.idea.tests.gui.framework.fixture.WorkBenchLoadingPanelFixture;
import com.android.tools.idea.tests.gui.framework.fixture.designer.layout.*;
import com.android.tools.idea.tests.gui.framework.fixture.designer.naveditor.DestinationListFixture;
import com.android.tools.idea.tests.gui.framework.fixture.designer.naveditor.HostPanelFixture;
import com.android.tools.idea.tests.gui.framework.fixture.designer.naveditor.NavDesignSurfaceFixture;
import com.android.tools.idea.tests.gui.framework.matcher.Matchers;
import com.android.tools.idea.uibuilder.structure.BackNavigationComponent;
import com.android.tools.idea.uibuilder.surface.NlDesignSurface;
import com.intellij.openapi.actionSystem.ActionToolbar;
import com.intellij.openapi.actionSystem.impl.ActionToolbarImpl;
import org.fest.swing.core.ComponentDragAndDrop;
import org.fest.swing.core.MouseButton;
import org.fest.swing.core.Robot;
import org.fest.swing.fixture.JMenuItemFixture;
import org.fest.swing.fixture.JPanelFixture;
import org.fest.swing.fixture.JPopupMenuFixture;
import org.fest.swing.fixture.JTreeFixture;
import org.fest.swing.timing.Pause;
import org.fest.swing.timing.Wait;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import java.awt.*;
import java.awt.event.InputEvent;
import java.util.List;

import static junit.framework.TestCase.assertTrue;

/**
 * Fixture wrapping the the layout editor for a particular file
 */
public class NlEditorFixture extends ComponentFixture<NlEditorFixture, NlEditorPanel> {
  private final DesignSurfaceFixture<? extends DesignSurfaceFixture, ? extends DesignSurface> myDesignSurfaceFixture;
  private NlPropertyInspectorFixture myPropertyFixture;
  private NlPaletteFixture myPaletteFixture;
  private WorkBenchLoadingPanelFixture myLoadingPanelFixture;
  private final ComponentDragAndDrop myDragAndDrop;

  public NlEditorFixture(@NotNull Robot robot, @NotNull NlEditor editor) {
    super(NlEditorFixture.class, robot, editor.getComponent());
    DesignSurface surface = editor.getComponent().getSurface();
    if (surface instanceof NlDesignSurface) {
      myDesignSurfaceFixture = new NlDesignSurfaceFixture(robot, (NlDesignSurface)surface);
    }
    else if (surface instanceof NavDesignSurface) {
      myDesignSurfaceFixture = new NavDesignSurfaceFixture(robot, (NavDesignSurface)surface);
    }
    else {
      throw new RuntimeException("Unsupported DesignSurface type " + surface.getClass().getName());
    }
    myDragAndDrop = new ComponentDragAndDrop(robot);

    myLoadingPanelFixture = new WorkBenchLoadingPanelFixture(robot, target().getWorkBench().getLoadingPanel());
  }

  @NotNull
  public NlEditorFixture waitForRenderToFinish() {
    waitForRenderToFinish(Wait.seconds(10));
    return this;
  }

  @NotNull
  public NlEditorFixture waitForRenderToFinish(@NotNull Wait wait) {
    myDesignSurfaceFixture.waitForRenderToFinish(wait);
    wait.expecting("WorkBench is showing").until(() -> !myLoadingPanelFixture.isLoading());
    // Fade out of the loading panel takes 500ms
    Pause.pause(1000);
    return this;
  }

  @NotNull
  public NlComponentFixture findView(@NotNull String tag, int occurrence) {
    return ((NlDesignSurfaceFixture)myDesignSurfaceFixture).findView(tag, occurrence);
  }

  @NotNull
  public List<NlComponent> getSelection() {
    return myDesignSurfaceFixture.getSelection();
  }

  public double getScale() {
    return myDesignSurfaceFixture.getScale();
  }

  /**
   * Waits for the design tab of the layout editor to either load (waiting for sync and indexing
   * to complete if necessary) or display an error message. Callers can check whether or not the
   * design editor is usable after this method completes by calling canInteractWithSurface().
   *
   * @see #canInteractWithSurface()
   */
  @NotNull
  public NlEditorFixture waitForSurfaceToLoad() {
    // A long timeout is necessary in case the IDE needs to perform indexing (since the design surface will not render
    // until sync and indexing are finished). We can't simply wait for background tasks to complete before calling
    // this method because there might be a delay between the sync and indexing steps that causes the wait to finish prematurely.
    Wait.seconds(90).expecting("Design surface finished loading").until(() -> {
      if (myLoadingPanelFixture.hasError()) {
        return true;
      }

      return myDesignSurfaceFixture.target().isShowing();
    });

    return this;
  }

  public boolean canInteractWithSurface() {
    return !myLoadingPanelFixture.hasError() && myDesignSurfaceFixture.target().isShowing();
  }

  public NlEditorFixture assertCanInteractWithSurface() {
    assertTrue(canInteractWithSurface());
    return this;
  }

  public boolean hasRenderErrors() {
    return myDesignSurfaceFixture.hasRenderErrors();
  }

  public void waitForErrorPanelToContain(@NotNull String errorText) {
    myDesignSurfaceFixture.waitForErrorPanelToContain(errorText);
  }

  @NotNull
  public DesignSurfaceFixture getSurface() {
    return myDesignSurfaceFixture;
  }

  @NotNull
  public NavDesignSurfaceFixture getNavSurface() {
    return (NavDesignSurfaceFixture)myDesignSurfaceFixture;
  }

  @NotNull
  public NlConfigurationToolbarFixture<NlEditorFixture> getConfigToolbar() {
    ActionToolbar toolbar = robot().finder().findByName(target(), "NlConfigToolbar", ActionToolbarImpl.class);
    return new NlConfigurationToolbarFixture<>(this, robot(), myDesignSurfaceFixture.target(), toolbar);
  }

  @NotNull
  public NlViewActionToolbarFixture getComponentToolbar() {
    return NlViewActionToolbarFixture.create(this);
  }

  @NotNull
  public CreateResourceDirectoryDialogFixture getSelectResourceDirectoryDialog() {
    return new CreateResourceDirectoryDialogFixture(robot());
  }

  @NotNull
  public NlPropertyInspectorFixture getPropertiesPanel() {
    if (myPropertyFixture == null) {
      myPropertyFixture = new NlPropertyInspectorFixture(robot(), NlPropertyInspectorFixture.create(robot()));
    }
    return myPropertyFixture;
  }

  @NotNull
  public NlRhsConfigToolbarFixture getRhsConfigToolbar() {
    ActionToolbarImpl toolbar = robot().finder().findByName(target(), "NlRhsConfigToolbar", ActionToolbarImpl.class);
    return new NlRhsConfigToolbarFixture(this, toolbar);
  }

  @NotNull
  public JTreeFixture getComponentTree() {
    JTreeFixture fixture = new JTreeFixture(robot(), (JTree)robot().finder().findByName(target(), "componentTree"));

    fixture.replaceCellReader((tree, value) -> {
      return ((NlComponent)value).getTagName();
    });

    Wait.seconds(10)
      .expecting("component tree to be populated")
      .until(() -> fixture.target().getPathForRow(0) != null);

    return fixture;
  }

  @NotNull
  public JPanelFixture getBackNavigationPanel() {
    return new JPanelFixture(robot(), BackNavigationComponent.BACK_NAVIGATION_COMPONENT_NAME);
  }

  @NotNull
  public NlPaletteFixture getPalette() {
    if (myPaletteFixture == null) {
      Wait.seconds(10).expecting("WorkBench is showing").until(() -> myDesignSurfaceFixture.target().isShowing());
      Container workBench = SwingUtilities.getAncestorOfClass(WorkBench.class, myDesignSurfaceFixture.target());
      myPaletteFixture = NlPaletteFixture.create(robot(), workBench);
    }
    return myPaletteFixture;
  }

  @NotNull
  public NlEditorFixture dragComponentToSurface(@NotNull String group, @NotNull String item, int relativeX, int relativeY) {
    getPalette().dragComponent(group, item);

    DesignSurface target = myDesignSurfaceFixture.target();
    SceneView sceneView = target.getCurrentSceneView();

    myDragAndDrop
      .drop(target, new Point(sceneView.getX() + relativeX, sceneView.getY() + relativeY));

    // Wait for the button to settle. It sometimes moves after being dropped onto the canvas.
    robot().waitForIdle();
    return this;
  }

  @NotNull
  public NlEditorFixture dragComponentToSurface(@NotNull String group, @NotNull String item) {
    DesignSurface target = myDesignSurfaceFixture.target();
    SceneView sceneView = target.getCurrentSceneView();

    dragComponentToSurface(group, item, sceneView.getSize().width / 2, sceneView.getSize().height / 2);
    return this;
  }

  /**
   * Moves the mouse to the resize corner of the screen view, and presses the left mouse button.
   * That starts the canvas resize interaction.
   *
   * @see #resizeToAndroidSize(int, int)
   * @see #endResizeInteraction()
   */
  @NotNull
  public NlEditorFixture startResizeInteraction() {
    DesignSurface surface = myDesignSurfaceFixture.target();
    SceneView screenView = surface.getCurrentSceneView();

    Dimension size = screenView.getSize();
    robot().pressMouse(surface, new Point(screenView.getX() + size.width + 24, screenView.getY() + size.height + 24));
    return this;
  }

  /**
   * Moves the mouse to resize the screen view to correspond to a device of size {@code (width, height)}, expressed in dp
   *
   * @see #startResizeInteraction()
   * @see #endResizeInteraction()
   */
  @NotNull
  public NlEditorFixture resizeToAndroidSize(@AndroidDpCoordinate int width, @AndroidDpCoordinate int height) {
    DesignSurface surface = myDesignSurfaceFixture.target();
    SceneView screenView = surface.getCurrentSceneView();

    robot().moveMouse(surface, Coordinates.getSwingXDip(screenView, width), Coordinates.getSwingYDip(screenView, height));
    return this;
  }

  /**
   * Releases left mouse button to end resize interaction.
   *
   * @see #startResizeInteraction()
   * @see #resizeToAndroidSize(int, int)
   */
  @NotNull
  public NlEditorFixture endResizeInteraction() {
    robot().releaseMouse(MouseButton.LEFT_BUTTON);
    return this;
  }

  /**
   * Ensures only the design view is being displayed, and zooms to fit.
   * Only applicable if {@code target()} is a {@link NlDesignSurface}.
   */
  @NotNull
  public NlEditorFixture showOnlyDesignView() {
    getConfigToolbar().selectDesign();
    getRhsConfigToolbar().zoomToFit();
    return this;
  }

  /**
   * Ensures only the blueprint view is being displayed, and zooms to fit.
   * Only applicable if {@code target()} is a {@link NlDesignSurface}.
   */
  @NotNull
  public NlEditorFixture showOnlyBlueprintView() {
    getConfigToolbar().selectBlueprint();
    getRhsConfigToolbar().zoomToFit();
    return this;
  }

  @NotNull
  public NlEditorFixture mouseWheelZoomIn(int amount) {
    robot().click(myDesignSurfaceFixture.target());
    robot().pressModifiers(InputEvent.CTRL_MASK);
    robot().rotateMouseWheel(myDesignSurfaceFixture.target(), amount);
    robot().releaseModifiers(InputEvent.CTRL_MASK);
    return this;
  }

  @NotNull
  public NlEditorFixture mouseWheelScroll(int amount) {
    robot().click(myDesignSurfaceFixture.target());
    robot().rotateMouseWheel(myDesignSurfaceFixture.target(), amount);
    return this;
  }

  public void dragMouseFromCenter(int dx, int dy, @NotNull MouseButton mouseButton, int modifiers) {
    DesignSurface surface = myDesignSurfaceFixture.target();
    robot().moveMouse(surface);
    robot().pressModifiers(modifiers);
    robot().pressMouse(mouseButton);
    robot().moveMouse(surface, surface.getWidth() / 2 + dx, surface.getHeight() / 2 + dy);
    robot().releaseMouseButtons();
    robot().releaseModifiers(modifiers);
  }

  @NotNull
  public List<NlComponentFixture> getAllComponents() {
    return myDesignSurfaceFixture.getAllComponents();
  }

  @NotNull
  public Point getScrollPosition() {
    return myDesignSurfaceFixture.target().getScrollPosition();
  }

  @NotNull
  public IssuePanelFixture getIssuePanel() {
    return myDesignSurfaceFixture.getIssuePanelFixture();
  }

  public void enlargeBottomComponentSplitter() {
    target().setIssuePanelProportion(0.2f);
  }

  @NotNull
  public MorphDialogFixture findMorphDialog() {
    return new MorphDialogFixture(robot());
  }

  /**
   * Returns the popup menu item for the provided component in the component tree
   */
  @NotNull
  public JPopupMenuFixture getTreePopupMenuItemForComponent(@NotNull NlComponent component) {
    return getComponentTree().showPopupMenuAt(buildTreePathTo(component));
  }

  /**
   * Build the string representation of the path to the provided component in the component tree
   */
  @NotNull
  private static String buildTreePathTo(NlComponent current) {
    StringBuilder builder = new StringBuilder(current.getTag().getName());
    while ((current = current.getParent()) != null) {
      builder.insert(0, current.getTag().getName() + "/");
      current = current.getParent();
    }
    return builder.toString();
  }

  public void invokeContextMenuAction(@NotNull String actionLabel) {
    new JMenuItemFixture(robot(), GuiTests.waitUntilShowing(robot(), Matchers.byText(JMenuItem.class, actionLabel))).click();
  }

  public HostPanelFixture hostPanel() {
    return HostPanelFixture.Companion.create(robot());
  }

  public DestinationListFixture destinationList() {
    return DestinationListFixture.Companion.create(robot());
  }
}
