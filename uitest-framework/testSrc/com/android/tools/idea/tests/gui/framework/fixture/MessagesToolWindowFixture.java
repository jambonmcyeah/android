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
package com.android.tools.idea.tests.gui.framework.fixture;

import com.google.common.base.Joiner;
import com.google.common.base.Splitter;
import com.intellij.ide.errorTreeView.ErrorTreeElement;
import com.intellij.ide.errorTreeView.ErrorTreeElementKind;
import com.intellij.ide.errorTreeView.ErrorViewStructure;
import com.intellij.ide.errorTreeView.GroupingElement;
import com.intellij.ide.errorTreeView.NewErrorTreeViewPanel;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.externalSystem.service.notification.EditableNotificationMessageElement;
import com.intellij.openapi.externalSystem.service.notification.NotificationMessageElement;
import com.intellij.openapi.fileEditor.OpenFileDescriptor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Pair;
import com.intellij.openapi.wm.ToolWindowId;
import com.intellij.pom.Navigatable;
import com.intellij.ui.content.Content;
import org.fest.swing.core.Robot;
import org.fest.swing.edt.GuiQuery;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.JEditorPane;
import javax.swing.event.HyperlinkEvent;
import javax.swing.tree.TreeCellEditor;
import java.io.File;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.google.common.truth.Truth.assertThat;
import static com.intellij.openapi.vfs.VfsUtilCore.virtualToIoFile;
import static javax.swing.event.HyperlinkEvent.EventType.ACTIVATED;
import static org.fest.reflect.core.Reflection.field;
import static org.fest.swing.awt.AWT.visibleCenterOf;
import static org.junit.Assert.assertNotNull;

public class MessagesToolWindowFixture extends ToolWindowFixture {
  MessagesToolWindowFixture(@NotNull Project project, @NotNull Robot robot) {
    super(ToolWindowId.MESSAGES_WINDOW, project, robot);
  }

  @NotNull
  public ContentFixture getGradleSyncContent() {
    return new SyncContentFixture(getContent("Gradle Sync"));
  }

  public abstract static class ContentFixture {
    @NotNull private final Content myContent;

    private ContentFixture(@NotNull Content content) {
      myContent = content;
    }

    @NotNull
    public MessageFixture findMessageContainingText(@NotNull ErrorTreeElementKind kind, @NotNull final String text) {
      ErrorTreeElement element = doFindMessage(kind, new MessageMatcher() {
        @Override
        protected boolean matches(@NotNull String[] lines) {
          for (String s : lines) {
            if (s.contains(text)) {
              return true;
            }
          }
          return false;
        }
      });
      return createFixture(element);
    }

    @NotNull
    public MessageFixture findMessage(@NotNull ErrorTreeElementKind kind, @NotNull MessageMatcher matcher) {
      ErrorTreeElement found = doFindMessage(kind, matcher);
      return createFixture(found);
    }

    @NotNull
    protected abstract MessageFixture createFixture(@NotNull ErrorTreeElement element);

    @NotNull
    private ErrorTreeElement doFindMessage(@NotNull ErrorTreeElementKind kind, @NotNull MessageMatcher matcher) {
      return GuiQuery.getNonNull(() -> {
        ErrorViewStructure errorView = ((NewErrorTreeViewPanel)myContent.getComponent()).getErrorViewStructure();
        return findMessage(errorView, errorView.getChildElements(errorView.getRootElement()), matcher, kind);
      });
    }

    @Nullable
    private static ErrorTreeElement findMessage(@NotNull ErrorViewStructure errorView,
                                                @NotNull ErrorTreeElement[] children,
                                                @NotNull MessageMatcher matcher,
                                                @NotNull ErrorTreeElementKind kind) {
      for (ErrorTreeElement child : children) {
        if (child instanceof GroupingElement) {
          ErrorTreeElement found = findMessage(errorView, errorView.getChildElements(child), matcher, kind);
          if (found != null) {
            return found;
          }
        }
        if (kind == child.getKind() && matcher.matches(child.getText())) {
          return child;
        }
      }
      return null;
    }
  }

  public static abstract class MessageMatcher {
    protected abstract boolean matches(@NotNull String[] text);

    @NotNull
    public static MessageMatcher firstLineStartingWith(@NotNull String prefix) {
      return new MessageMatcher() {
        @Override
        public boolean matches(@NotNull String[] text) {
          assertThat(text).isNotEmpty();
          return text[0].startsWith(prefix);
        }

        @Override
        public String toString() {
          return "first line starting with '" + prefix + "'";
        }
      };
    }
  }

  public class SyncContentFixture extends ContentFixture {
    SyncContentFixture(@NotNull Content content) {
      super(content);
    }

    @Override
    @NotNull
    protected MessageFixture createFixture(@NotNull ErrorTreeElement element) {
      return new SyncMessageFixture(myRobot, element);
    }
  }

  public abstract static class MessageFixture {
    private static final Pattern ANCHOR_TAG_PATTERN = Pattern.compile("<a href=\"(.*?)\">([^<]+)</a>");

    @NotNull protected final Robot myRobot;
    @NotNull protected final ErrorTreeElement myTarget;

    protected MessageFixture(@NotNull Robot robot, @NotNull ErrorTreeElement target) {
      myRobot = robot;
      myTarget = target;
    }

    @NotNull
    public abstract HyperlinkFixture findHyperlink(@NotNull String hyperlinkText);

    @NotNull
    public abstract HyperlinkFixture findHyperlinkByContainedText(@NotNull String hyperlinkText);

    @NotNull
    protected String extractUrl(@NotNull String wholeText, @NotNull String hyperlinkText) {
      String url = null;
      Matcher matcher = ANCHOR_TAG_PATTERN.matcher(wholeText);
      while (matcher.find()) {
        String anchorText = matcher.group(2);
        // Text may be spread across multiple lines. Put everything in one line.
        if (anchorText != null) {
          anchorText = anchorText.replaceAll("[\\s]+", " ");
          if (anchorText.equals(hyperlinkText)) {
            url = matcher.group(1);
            break;
          }
        }
      }
      assertNotNull("Failed to find URL for hyperlink '" + hyperlinkText + "'", url);
      return url;
    }

    @NotNull
    protected String extractUrlByContainedText(@NotNull String wholeText, @NotNull String hyperlinkText) {
      String url = null;
      Matcher matcher = ANCHOR_TAG_PATTERN.matcher(wholeText);
      while (matcher.find()) {
        String anchorText = matcher.group(2);
        // Text may be spread across multiple lines. Put everything in one line.
        if (anchorText != null) {
          anchorText = anchorText.replaceAll("[\\s]+", " ");
          if (anchorText.contains(hyperlinkText)) {
            url = matcher.group(1);
            break;
          }
        }
      }
      assertNotNull("Failed to find URL for hyperlink '" + hyperlinkText + "'", url);
      return url;
    }

    @NotNull
    public MessageFixture requireLocation(@NotNull File filePath, int line) {
      doRequireLocation(filePath, line);
      return this;
    }

    protected void doRequireLocation(@NotNull File expectedFilePath, int line) {
      assertThat(myTarget).isInstanceOf(NotificationMessageElement.class);
      NotificationMessageElement element = (NotificationMessageElement)myTarget;

      Navigatable navigatable = element.getNavigatable();
      assertThat(navigatable).isInstanceOf(OpenFileDescriptor.class);

      OpenFileDescriptor descriptor = (OpenFileDescriptor)navigatable;
      File actualFilePath = virtualToIoFile(descriptor.getFile());
      assertThat(actualFilePath).isEqualTo(expectedFilePath);

      assertThat((descriptor.getLine() + 1)).named("line").isEqualTo(line); // descriptor line is zero-based.
    }

    @NotNull
    public abstract String getText();
  }

  public static class SyncMessageFixture extends MessageFixture {
    SyncMessageFixture(@NotNull Robot robot, @NotNull ErrorTreeElement target) {
      super(robot, target);
    }

    @Override
    @NotNull
    public HyperlinkFixture findHyperlink(@NotNull String hyperlinkText) {
      Pair<JEditorPane, String> cellEditorAndText = getCellEditorAndText();
      String url = extractUrl(cellEditorAndText.getSecond(), hyperlinkText);
      return new SyncHyperlinkFixture(myRobot, url, cellEditorAndText.getFirst());
    }

    @Override
    @NotNull
    public HyperlinkFixture findHyperlinkByContainedText(@NotNull String hyperlinkText) {
      Pair<JEditorPane, String> cellEditorAndText = getCellEditorAndText();
      String url = extractUrlByContainedText(cellEditorAndText.getSecond(), hyperlinkText);
      return new SyncHyperlinkFixture(myRobot, url, cellEditorAndText.getFirst());
    }

    @Override
    @NotNull
    public String getText() {
      String html = getCellEditorAndText().getSecond();

      int startBodyIndex = html.indexOf("<body>");
      assertThat(startBodyIndex).isAtLeast(0);

      int endBodyIndex = html.indexOf("</body>");
      assertThat(endBodyIndex).isGreaterThan(startBodyIndex);

      String body = html.substring(startBodyIndex + 6 /* 6 = length of '<body>' */, endBodyIndex);
      List<String> lines = Splitter.on('\n').omitEmptyStrings().trimResults().splitToList(body);
      body = Joiner.on(' ').join(lines);

      return body;
    }

    @NotNull
    private Pair<JEditorPane, String> getCellEditorAndText() {
      // There is no specific UI component for a hyperlink in the "Messages" window. Instead we have a JEditorPane with HTML. This method
      // finds the anchor tags, and matches the text of each of them against the given text. If a matching hyperlink is found, we fire a
      // HyperlinkEvent, simulating a click on the actual hyperlink.
      assertThat(myTarget).isInstanceOf(EditableNotificationMessageElement.class);

      JEditorPane editorComponent = GuiQuery.getNonNull(() -> {
        TreeCellEditor cellEditor = ((EditableNotificationMessageElement)myTarget).getRightSelfEditor();
        return field("editorComponent").ofType(JEditorPane.class).in(cellEditor).get();
      });
      String text = GuiQuery.getNonNull(editorComponent::getText);
      return Pair.create(editorComponent, text);
    }
  }

  public abstract static class HyperlinkFixture {
    @NotNull protected final Robot myRobot;
    @NotNull protected final String myUrl;

    protected HyperlinkFixture(@NotNull Robot robot, @NotNull String url) {
      myRobot = robot;
      myUrl = url;
    }

    @NotNull
    public HyperlinkFixture requireUrl(@NotNull String expected) {
      assertThat(myUrl).named("URL").isEqualTo(expected);
      return this;
    }

    @NotNull
    public HyperlinkFixture click() {
      click(true);
      return this;
    }

    /**
     * Simulates a click on the hyperlink. This method returns immediately and does not wait for any UI actions triggered by the click to be
     * finished.
     */
    public HyperlinkFixture clickAndContinue() {
      click(false);
      return this;
    }

    private void click(boolean synchronous) {
      if (synchronous) {
        ApplicationManager.getApplication().invokeAndWait(this::doClick);
      }
      else {
        ApplicationManager.getApplication().invokeLater(this::doClick);
      }
    }

    protected abstract void doClick();
  }

  public static class SyncHyperlinkFixture extends HyperlinkFixture {
    @NotNull private final JEditorPane myTarget;

    SyncHyperlinkFixture(@NotNull Robot robot, @NotNull String url, @NotNull JEditorPane target) {
      super(robot, url);
      myTarget = target;
    }

    @Override
    protected void doClick() {
      // at least move the mouse where the message is, so we can know that something is happening.
      myRobot.moveMouse(visibleCenterOf(myTarget));
      myTarget.fireHyperlinkUpdate(new HyperlinkEvent(this, ACTIVATED, null, myUrl));
    }
  }
}