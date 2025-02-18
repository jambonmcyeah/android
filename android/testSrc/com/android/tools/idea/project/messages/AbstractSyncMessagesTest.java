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
package com.android.tools.idea.project.messages;

import com.intellij.openapi.externalSystem.model.ProjectSystemId;
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskId;
import com.intellij.openapi.project.Project;
import com.intellij.testFramework.JavaProjectTestCase;
import org.jetbrains.annotations.NotNull;

import static com.intellij.openapi.externalSystem.model.ProjectSystemId.IDE;
import static com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskType.EXECUTE_TASK;
import static com.android.tools.idea.gradle.project.sync.idea.IdeaGradleSync.LAST_SYNC_TASK_ID_KEY;
import static org.mockito.MockitoAnnotations.initMocks;

/**
 * Tests for {@link AbstractSyncMessages}.
 */
public class AbstractSyncMessagesTest extends JavaProjectTestCase {
  private static final String TEST_GROUP = "Test";
  private SyncMessages mySyncMessages;

  @Override
  protected void setUp() throws Exception {
    super.setUp();

    initMocks(this);

    mySyncMessages = new SyncMessages(myProject);
    ExternalSystemTaskId taskId = ExternalSystemTaskId.create(mySyncMessages.getProjectSystemId(), EXECUTE_TASK, myProject);
    myProject.putUserData(LAST_SYNC_TASK_ID_KEY, taskId);
  }

  @Override
  protected void tearDown() throws Exception {
    try {
      myProject.putUserData(LAST_SYNC_TASK_ID_KEY, null);
    }
    finally {
      super.tearDown();
    }
  }

  public void testGetErrorCount() {
    int expectedCount = 6;
    generateMessages(expectedCount, MessageType.ERROR);
    generateMessages(expectedCount + 1, MessageType.WARNING);
    int actualCount = mySyncMessages.getErrorCount();
    assertEquals(expectedCount, actualCount);
    assertEquals(2 * expectedCount + 1, mySyncMessages.getMessageCount(TEST_GROUP));
  }

  public void testGetMessageCount() {
    int expectedErrors = 6;
    int expectedWarnings = 9;
    generateMessages(expectedErrors, MessageType.ERROR);
    generateMessages(expectedWarnings, MessageType.WARNING);

    int actualCount = mySyncMessages.getMessageCount(TEST_GROUP);
    assertEquals(expectedErrors + expectedWarnings, actualCount);
  }

  public void testIsEmptyWithMessages() {
    generateMessages(1, MessageType.ERROR);
    assertFalse(mySyncMessages.isEmpty());
  }

  public void testIsEmptyWithoutMessages() {
    assertTrue(mySyncMessages.isEmpty());
  }

  public void testRemoveAllMessages() {
    int numErrors = 7;
    int numWarning = 13;
    generateMessages(numWarning, MessageType.WARNING);
    generateMessages(numErrors, MessageType.ERROR);
    assertFalse(mySyncMessages.isEmpty());
    mySyncMessages.removeAllMessages();
    assertTrue(mySyncMessages.isEmpty());
  }

  private void generateMessages(int count, MessageType type) {
    for (int i = 0; i < count; i++) {
      mySyncMessages.report(new SyncMessage("Test", type, "Message for test"));
    }
  }

  private static class SyncMessages extends AbstractSyncMessages {
    SyncMessages(@NotNull Project project) {
      super(project);
    }

    @Override
    @NotNull
    protected ProjectSystemId getProjectSystemId() {
      return IDE;
    }
  }
}