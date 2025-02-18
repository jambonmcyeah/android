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
package com.android.tools.idea.gradle.project.sync.setup.post.upgrade;

import com.android.tools.idea.gradle.plugin.AndroidPluginGeneration;
import com.android.tools.idea.gradle.plugin.AndroidPluginInfo;
import com.intellij.openapi.util.Disposer;
import com.intellij.testFramework.JavaProjectTestCase;
import org.mockito.Mock;

import java.util.UUID;

import static com.google.common.truth.Truth.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.MockitoAnnotations.initMocks;

/**
 * Tests for {@link ForcedPluginPreviewVersionUpgradeDialog}
 */
public class ForcedPluginPreviewVersionUpgradeDialogTest extends JavaProjectTestCase {
  @Mock private AndroidPluginInfo myPluginInfo;
  @Mock private AndroidPluginGeneration myPluginGeneration;

  private ForcedPluginPreviewVersionUpgradeDialog myDialog;

  @Override
  protected void setUp() throws Exception {
    super.setUp();
    initMocks(this);
    when(myPluginInfo.getPluginGeneration()).thenReturn(myPluginGeneration);
  }

  @Override
  protected void tearDown() throws Exception {
    try {
      if (myDialog != null) {
        Disposer.dispose(myDialog.getDisposable());
      }
    }
    finally {
      super.tearDown();
    }
  }

  public void testDialogMessage() {
    String randomVersion = UUID.randomUUID().toString();
    when(myPluginGeneration.getLatestKnownVersion()).thenReturn(randomVersion);

    myDialog = new ForcedPluginPreviewVersionUpgradeDialog(getProject(), myPluginInfo);
    String message = myDialog.getDisplayedMessage();

    assertThat(message).contains("the IDE will update the plugin to version " + randomVersion + ".");
    verify(myPluginGeneration).getLatestKnownVersion();
  }
}