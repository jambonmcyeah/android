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
package com.android.tools.idea.tests.gui.uibuilder;

import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertEquals;

import android.view.View;
import com.android.tools.idea.flags.StudioFlags;
import com.android.tools.idea.tests.gui.framework.BuildSpecificGuiTestRunner;
import com.android.tools.idea.tests.gui.framework.GuiTestRule;
import com.android.tools.idea.tests.gui.framework.RunIn;
import com.android.tools.idea.tests.gui.framework.ScreenshotsDuringTest;
import com.android.tools.idea.tests.gui.framework.TestGroup;
import com.android.tools.idea.tests.gui.framework.fixture.EditorFixture;
import com.android.tools.idea.tests.gui.framework.fixture.IdeFrameFixture;
import com.android.tools.idea.tests.gui.framework.fixture.MessagesFixture;
import com.android.tools.idea.tests.gui.framework.fixture.designer.NlComponentFixture;
import com.android.tools.idea.tests.gui.framework.fixture.designer.NlEditorFixture;
import com.android.tools.idea.tests.gui.framework.fixture.designer.layout.MorphDialogFixture;
import com.android.tools.idea.tests.gui.framework.guitestprojectsystem.TargetBuildSystem;
import com.intellij.ide.ui.UISettings;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.util.Computable;
import java.awt.Point;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.io.IOException;
import org.fest.swing.core.MouseButton;
import org.fest.swing.fixture.JPopupMenuFixture;
import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

@RunWith(Parameterized.class)
@Parameterized.UseParametersRunnerFactory(BuildSpecificGuiTestRunner.Factory.class)
public class NlEditorTest {
  @Rule public final GuiTestRule guiTest = new GuiTestRule();
  @Rule public final ScreenshotsDuringTest movieRule = new ScreenshotsDuringTest();

  @Parameterized.Parameters(name="{0}")
  public static TargetBuildSystem.BuildSystem[] data() {
    return TargetBuildSystem.BuildSystem.values();
  }

  @Test
  public void testSelectComponent() throws Exception {
    guiTest.importSimpleLocalApplication();

    // Open file as XML and switch to design tab, wait for successful render
    EditorFixture editor = guiTest.ideFrame().getEditor();
    editor.open("app/src/main/res/layout/activity_my.xml", EditorFixture.Tab.DESIGN);

    NlEditorFixture layout = editor.getLayoutEditor(false);
    layout.waitForRenderToFinish();

    // Find and click the first text view
    NlComponentFixture textView = layout.findView("TextView", 0);
    textView.click();

    // It should be selected now
    assertThat(layout.getSelection()).containsExactly(textView.getComponent());
  }

  @TargetBuildSystem({TargetBuildSystem.BuildSystem.BAZEL})
  @Ignore("b/78228400")
  @Test
  public void designEditorUnavailableIfInProgressBazelSyncFailed() throws Exception {
    // Add a bad dependency to app/BUILD. This will cause the next sync to fail.
    guiTest.importSimpleLocalApplication()
      .getEditor()
      .open("app/BUILD")
      .moveBetween("deps = [", "")
      .enterText("\n\":bogus_dependency\",");

    guiTest.testSystem()
           .requestProjectSync(guiTest.ideFrame())
           .waitForProjectSyncToStart(guiTest.ideFrame());

    // Open design editor while sync is in progress. We should see a loading panel
    // while the sync is taking place. Then, once the sync fails, the loading
    // animation should be replaced with an error message.
    NlEditorFixture editorFixture = guiTest.ideFrame().getEditor()
      .open("app/src/main/res/layout/activity_my.xml", EditorFixture.Tab.DESIGN)
      .getLayoutEditor(false)
      .waitForSurfaceToLoad();

    // The design editor should be unavailable because the in-progress sync failed.
    assertThat(editorFixture.canInteractWithSurface()).isFalse();
  }

  @TargetBuildSystem({TargetBuildSystem.BuildSystem.BAZEL})
  @Ignore("b/78228400")
  @Test
  public void designEditorUnavailableIfLastBazelSyncFailed() throws Exception {
    // Add a bad dependency to app/BUILD. This will cause the next sync to fail.
    guiTest.importSimpleLocalApplication()
      .getEditor()
      .open("app/BUILD")
      .moveBetween("deps = [", "")
      .enterText("\n\":bogus_dependency\",");

    guiTest.testSystem()
      .requestProjectSync(guiTest.ideFrame())
      .waitForProjectSyncToFinish(guiTest.ideFrame());

    // After the failing sync, open the design editor.
    NlEditorFixture editorFixture = guiTest.ideFrame().getEditor()
      .open("app/src/main/res/layout/activity_my.xml", EditorFixture.Tab.DESIGN)
      .getLayoutEditor(false)
      .waitForSurfaceToLoad();

    // The design editor should be unavailable because the last sync failed.
    assertThat(editorFixture.canInteractWithSurface()).isFalse();
  }

  @Test
  public void testCopyAndPaste() throws Exception {
    guiTest.importSimpleLocalApplication();
    IdeFrameFixture ideFrame = guiTest.ideFrame();
    EditorFixture editor = ideFrame.getEditor()
      .open("app/src/main/res/layout/activity_my.xml", EditorFixture.Tab.DESIGN);
    NlEditorFixture layout = editor.getLayoutEditor(true)
      .dragComponentToSurface("Buttons", "Button")
      .dragComponentToSurface("Buttons", "CheckBox")
      .waitForRenderToFinish();

    // Find and click the first text view
    NlComponentFixture textView = layout.findView("CheckBox", 0);
    textView.click();

    // It should be selected now
    assertThat(layout.getSelection()).containsExactly(textView.getComponent());
    assertEquals(4, layout.getAllComponents().size()); // 4 = root layout + 3 widgets

    ideFrame.invokeMenuPath("Edit", "Cut");
    assertThat(layout.getSelection()).isEmpty();
    assertEquals(3, layout.getAllComponents().size());

    layout.findView("Button", 0).click();
    ideFrame.invokeMenuPath("Edit", "Paste");
    layout.findView("CheckBox", 0).click();
    ideFrame.invokeMenuPath("Edit", "Copy");
    ideFrame.invokeMenuPath("Edit", "Paste");
    assertEquals(5, layout.getAllComponents().size());
  }

  @RunIn(TestGroup.UNRELIABLE)  // b/72573971
  @Test
  public void testZoomAndPanWithMouseShortcut() throws Exception {
    guiTest.importSimpleLocalApplication();
    IdeFrameFixture ideFrame = guiTest.ideFrame();
    EditorFixture editor = ideFrame.getEditor()
      .open("app/src/main/res/layout/activity_my.xml", EditorFixture.Tab.DESIGN);

    NlEditorFixture nele = editor.getLayoutEditor(true);
    nele.waitForRenderToFinish();

    // Test zoom in with mouse wheel
    double oldScale = nele.getScale();
    nele.mouseWheelZoomIn(-10);
    assertThat(nele.getScale()).isGreaterThan(oldScale);

    // Test Pan with middle mouse button
    Point oldScrollPosition = nele.getScrollPosition();
    nele.dragMouseFromCenter(-10, -10, MouseButton.MIDDLE_BUTTON, 0);
    Point expectedScrollPosition = new Point(oldScrollPosition);
    expectedScrollPosition.translate(10, 10);
    assertThat(nele.getScrollPosition()).isEqualTo(expectedScrollPosition);

    // Test Pan with Left mouse button and CTRL
    nele.dragMouseFromCenter(-10, -10, MouseButton.LEFT_BUTTON, InputEvent.CTRL_MASK);
    expectedScrollPosition.translate(10, 10);
    assertThat(nele.getScrollPosition()).isEqualTo(expectedScrollPosition);

    // Test zoom out with mouse wheel
    oldScale = nele.getScale();
    nele.mouseWheelZoomIn(3);
    assertThat(nele.getScale()).isLessThan(oldScale);
  }

  @Test
  public void testAddDesignLibrary() throws Exception {
    guiTest.importSimpleLocalApplication()
      .getEditor()
      .open("app/src/main/res/layout/activity_my.xml", EditorFixture.Tab.DESIGN)
      .getLayoutEditor(true)
      .dragComponentToSurface("Text", "TextInputLayout");
    MessagesFixture.findByTitle(guiTest.robot(), "Add Project Dependency").clickOk();
    IdeFrameFixture ideFrame = guiTest.ideFrame();

    ideFrame
      .waitForGradleProjectSyncToFinish()
      .getEditor()
      .open("app/build.gradle")
      .moveBetween("implementation 'com.android.support:design", "")
      .invokeAction(EditorFixture.EditorAction.DELETE_LINE)
      .invokeAction(EditorFixture.EditorAction.SAVE)
      .awaitNotification(
        "Gradle files have changed since last project sync. A project sync may be necessary for the IDE to work properly.");

    ideFrame
      .requestProjectSync()
      .waitForGradleProjectSyncToFinish();
  }

  @Test
  public void morphComponent() throws IOException {
    boolean morphViewActionEnabled = StudioFlags.NELE_CONVERT_VIEW.get();

    try {
      StudioFlags.NELE_CONVERT_VIEW.override(true);
      guiTest.importSimpleLocalApplication();
      IdeFrameFixture ideFrame = guiTest.ideFrame();
      EditorFixture editor = ideFrame.getEditor()
        .open("app/src/main/res/layout/activity_my.xml", EditorFixture.Tab.DESIGN);
      NlEditorFixture layout = editor.getLayoutEditor(true)
        .waitForRenderToFinish();

      // Test click on a suggestion
      NlComponentFixture textView = layout.findView("TextView", 0);
      textView.rightClick();
      textView.invokeContextMenuAction("Convert view...");
      MorphDialogFixture fixture = layout.findMorphDialog();
      fixture.getTextField().click();
      assertThat(fixture.getTextField().target().isFocusOwner()).isTrue();

      fixture.getSuggestionList().clickItem("Button");
      assertThat(fixture.getTextField().target().getText()).isEqualTo("Button");
      fixture.getOkButton().click();
      layout.waitForRenderToFinish();
      assertThat(textView.getComponent().getTag().getName()).isEqualTo("Button");

      // Test enter text manually
      NlComponentFixture button = layout.findView("Button", 0);
      button.rightClick();
      layout.invokeContextMenuAction("Convert view...");
      fixture = layout.findMorphDialog();
      fixture.getTextField().click();
      assertThat(fixture.getTextField().target().isFocusOwner()).isTrue();

      fixture.getTextField().replaceText("TextView");
      assertThat(fixture.getTextField().target().getText()).isEqualTo("TextView");
      fixture.getOkButton().click();
      layout.waitForRenderToFinish();
      assertThat(button.getComponent().getTag().getName()).isEqualTo("TextView");
    }
    finally {
      StudioFlags.NELE_CONVERT_VIEW.override(morphViewActionEnabled);
    }
  }

  @Test
  public void morphViewGroup() throws IOException {
    boolean morphViewActionEnabled = StudioFlags.NELE_CONVERT_VIEW.get();

    try {
      StudioFlags.NELE_CONVERT_VIEW.override(true);
      guiTest.importSimpleLocalApplication();
      IdeFrameFixture ideFrame = guiTest.ideFrame();
      EditorFixture editor = ideFrame.getEditor()
        .open("app/src/main/res/layout/absolute.xml", EditorFixture.Tab.DESIGN);
      NlEditorFixture layout = editor.getLayoutEditor(true).waitForRenderToFinish();

      // Right click on AbsoluteLayout in the component tree
      NlComponentFixture root = layout.findView("AbsoluteLayout", 0);
      JPopupMenuFixture popupMenu = layout.getTreePopupMenuItemForComponent(root.getComponent());

      // Open the convert view dialog
      popupMenu.menuItemWithPath("Convert view...").click();
      MorphDialogFixture fixture = layout.findMorphDialog();

      // Click the LinearLayout button
      fixture.getSuggestionList().clickItem("LinearLayout");

      // Apply change
      ideFrame.robot().pressAndReleaseKey(KeyEvent.VK_ENTER, 0);

      // Check if change is correctly applied:
      //    - Root name change from AbsoluteLayout to LinearLayout
      //    - Attributes specific to AbsoluteLayout are removed
      assertThat(root.getComponent().getTag().getName()).isEqualTo("LinearLayout");
      String expected = "<LinearLayout xmlns:android=\"http://schemas.android.com/apk/res/android\"\n" +
                        "    android:layout_width=\"match_parent\"\n" +
                        "    android:layout_height=\"match_parent\"\n" +
                        "    android:paddingLeft=\"16dp\"\n" +
                        "    android:paddingTop=\"16dp\"\n" +
                        "    android:paddingRight=\"16dp\"\n" +
                        "    android:paddingBottom=\"16dp\">\n" +
                        "\n" +
                        "    <Button\n" +
                        "        android:id=\"@+id/button\"\n" +
                        "        android:layout_width=\"wrap_content\"\n" +
                        "        android:layout_height=\"wrap_content\"\n" +
                        "        android:text=\"Button\" />\n" +
                        "\n" +
                        "    <Button\n" +
                        "        android:id=\"@+id/button2\"\n" +
                        "        android:layout_width=\"wrap_content\"\n" +
                        "        android:layout_height=\"wrap_content\"\n" +
                        "        android:text=\"Button\" />\n" +
                        "\n" +
                        "    <EditText\n" +
                        "        android:id=\"@+id/editText\"\n" +
                        "        android:layout_width=\"wrap_content\"\n" +
                        "        android:layout_height=\"wrap_content\"\n" +
                        "        android:ems=\"10\"\n" +
                        "        android:inputType=\"textPersonName\"\n" +
                        "        android:text=\"Name\" />\n" +
                        "\n" +
                        "    <LinearLayout\n" +
                        "        android:layout_width=\"359dp\"\n" +
                        "        android:layout_height=\"89dp\">\n" +
                        "\n" +
                        "        <Button\n" +
                        "            android:id=\"@+id/button3\"\n" +
                        "            android:layout_width=\"wrap_content\"\n" +
                        "            android:layout_height=\"wrap_content\"\n" +
                        "            android:layout_weight=\"1\"\n" +
                        "            android:text=\"Button\" />\n" +
                        "\n" +
                        "        <Button\n" +
                        "            android:id=\"@+id/button5\"\n" +
                        "            android:layout_width=\"wrap_content\"\n" +
                        "            android:layout_height=\"wrap_content\"\n" +
                        "            android:layout_weight=\"1\"\n" +
                        "            android:text=\"Button\" />\n" +
                        "\n" +
                        "        <Button\n" +
                        "            android:id=\"@+id/button6\"\n" +
                        "            android:layout_width=\"wrap_content\"\n" +
                        "            android:layout_height=\"wrap_content\"\n" +
                        "            android:layout_weight=\"1\"\n" +
                        "            android:text=\"Button\" />\n" +
                        "    </LinearLayout>\n" +
                        "</LinearLayout>";
      String text = ApplicationManager.getApplication()
        .runReadAction((Computable<String>)() -> root.getComponent().getTag().getText());
      assertThat(text).isEqualTo(expected);
    }
    finally {
      StudioFlags.NELE_CONVERT_VIEW.override(morphViewActionEnabled);
    }
  }

  @Test
  public void testNavigateEditorsWithoutTabs() throws Exception {
    // Regression test for b/37138939
    // When the tabs are turned off, a switch using ctrl-E (or cmd-E on Mac) to a layout/menu would
    // cause the editor to switch to design mode.

    UISettings settings = UISettings.getInstance();
    int placement = settings.getEditorTabPlacement();

    try {
      // First turn off tabs.
      settings.setEditorTabPlacement(UISettings.TABS_NONE);

      // Open up 2 layout files in design and switch them both to text editor mode.
      guiTest.importSimpleLocalApplication();
      IdeFrameFixture ideFrame = guiTest.ideFrame();
      EditorFixture editor = ideFrame.getEditor().open("app/src/main/res/layout/activity_my.xml", EditorFixture.Tab.DESIGN);
      editor.getLayoutEditor(true).waitForRenderToFinish();
      editor.switchToTab("Text");
      ideFrame.getEditor().open("app/src/main/res/layout/absolute.xml", EditorFixture.Tab.DESIGN);
      editor.getLayoutEditor(true).waitForRenderToFinish();
      editor.switchToTab("Text");

      // Switch to the previous layout and verify we are still editing the text.
      ideFrame.selectPreviousEditor();
      assertThat(editor.getCurrentFileName()).isEqualTo("activity_my.xml");
      assertThat(editor.getSelectedTab()).isEqualTo("Text");  // not "Design"

      // Again switch to the previous layout and verify we are still editing the text.
      ideFrame.selectPreviousEditor();
      assertThat(editor.getCurrentFileName()).isEqualTo("absolute.xml");
      assertThat(editor.getSelectedTab()).isEqualTo("Text");  // not "Design"
    }
    finally {
      settings.setEditorTabPlacement(placement);
    }
  }

  @Test
  public void gotoAction() throws IOException {
    guiTest.importSimpleLocalApplication();
    IdeFrameFixture ideFrame = guiTest.ideFrame();
    EditorFixture editor = ideFrame.getEditor()
      .open("app/src/main/res/layout/activity_my.xml", EditorFixture.Tab.DESIGN);

    NlEditorFixture nlEditorFixture = editor.getLayoutEditor(true);
    nlEditorFixture.rightClick();
    nlEditorFixture.invokeContextMenuAction("Go to XML");
    assertThat(editor.getSelectedTab()).isEqualTo("Text");
  }

  @Test
  public void scrollWhileZoomed() throws Exception {
    NlEditorFixture layoutEditor = guiTest.importProjectAndWaitForProjectSyncToFinish("LayoutLocalTest")
      .getEditor()
      .open("app/src/main/res/layout/scroll.xml", EditorFixture.Tab.DESIGN)
      .getLayoutEditor(true);
    Point surfacePosition = layoutEditor
      .waitForRenderToFinish()
      .showOnlyDesignView()
      .waitForRenderToFinish()
      .mouseWheelZoomIn(-10)
      .getScrollPosition();
    View view = (View)layoutEditor.findView("android.support.v4.widget.NestedScrollView", 0).getViewObject();
    assertThat(view.getScrollY()).isEqualTo(0);

    // Scroll a little bit: the ScrollView moves, the Surface stays.
    layoutEditor.mouseWheelScroll(10)
      .waitForRenderToFinish();
    int viewScroll = view.getScrollY();
    assertThat(viewScroll).isGreaterThan(0);
    assertThat(layoutEditor.getScrollPosition()).isEqualTo(surfacePosition);

    // Scroll a lot more so that the ScrollView reaches the bottom: the ScrollView moves, and the Surface moves as well.
    layoutEditor.mouseWheelScroll(1000)
      .waitForRenderToFinish();
    assertThat(view.getScrollY()).isGreaterThan(viewScroll);
    assertThat(layoutEditor.getScrollPosition().getY()).isGreaterThan(surfacePosition.getY());
  }
}
