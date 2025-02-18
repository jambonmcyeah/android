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
package com.android.tools.idea.gradle.project.sync;

import com.android.tools.idea.flags.StudioFlags;
import com.android.tools.idea.gradle.project.ProjectStructure;
import com.android.tools.idea.testing.AndroidGradleTestCase;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.project.Project;
import com.intellij.util.messages.MessageBus;
import org.jetbrains.android.facet.AndroidFacet;
import org.mockito.Mock;

import static com.android.tools.idea.gradle.project.sync.GradleSyncState.GRADLE_SYNC_TOPIC;
import static com.android.tools.idea.testing.TestProjectPaths.PROJECT_WITH_APPAND_LIB;
import static com.google.wireless.android.sdk.stats.GradleSyncStats.Trigger.TRIGGER_PROJECT_MODIFIED;
import static org.mockito.Mockito.*;
import static org.mockito.MockitoAnnotations.initMocks;

/**
 * Tests for {@link GradleSyncState}.
 */
public class GradleSyncStateIntegrationTest extends AndroidGradleTestCase {
  @Mock private GradleSyncListener myGradleSyncListener;

  private GradleSyncState mySyncState;

  @Override
  public void setUp() throws Exception {
    super.setUp();
    initMocks(this);
    Project project = getProject();

    MessageBus messageBus = mock(MessageBus.class);
    when(messageBus.syncPublisher(GRADLE_SYNC_TOPIC)).thenReturn(myGradleSyncListener);

    mySyncState = new GradleSyncState(project, GradleFiles.getInstance(project), messageBus,
                                      ProjectStructure.getInstance(project),
                                      new GradleSyncState.StateChangeNotification(project),
                                      new GradleSyncSummary(project));

  }

  public void testInvalidateLastSync() throws Exception {
    loadProject(PROJECT_WITH_APPAND_LIB);

    Module appModule = myModules.getAppModule();
    AndroidFacet appAndroidFacet = AndroidFacet.getInstance(appModule);
    assertNotNull(appAndroidFacet);
    assertNotNull(appAndroidFacet.getConfiguration().getModel());

    Module libModule = myModules.getModule("lib");
    AndroidFacet libAndroidFacet = AndroidFacet.getInstance(libModule);
    assertNotNull(libAndroidFacet);
    assertNotNull(libAndroidFacet.getConfiguration().getModel());

    mySyncState.setSyncStartedTimeStamp(0, TRIGGER_PROJECT_MODIFIED);
    mySyncState.invalidateLastSync("Error");
    assertTrue(mySyncState.lastSyncFailed());

    assertNull(appAndroidFacet.getConfiguration().getModel());
    assertNull(libAndroidFacet.getConfiguration().getModel());

    verify(myGradleSyncListener).syncFailed(getProject(), "Error");
  }

  public void testSyncErrorsFailSync() throws Exception {
    loadSimpleApplication();
    mySyncState.getSummary().setSyncErrorsFound(true);

    assertTrue(mySyncState.lastSyncFailed());
  }

  public void testCompoundSyncEnabled() throws Exception {
    try {
      StudioFlags.NEW_SYNC_INFRA_ENABLED.override(true);
      StudioFlags.SINGLE_VARIANT_SYNC_ENABLED.override(true);
      StudioFlags.COMPOUND_SYNC_ENABLED.override(true);

      loadSimpleApplication();

      // Project imported with no source generation
      verify(myGradleSyncListener, times(0)).sourceGenerationFinished(eq(getProject()));

      // Sync with source generation
      GradleSyncInvoker.getInstance().requestProjectSyncAndSourceGeneration(getProject(), TRIGGER_PROJECT_MODIFIED, myGradleSyncListener);

      verify(myGradleSyncListener).sourceGenerationFinished(eq(getProject()));
    }
    finally {
      StudioFlags.NEW_SYNC_INFRA_ENABLED.clearOverride();
      StudioFlags.SINGLE_VARIANT_SYNC_ENABLED.clearOverride();
      StudioFlags.COMPOUND_SYNC_ENABLED.clearOverride();
    }
  }
}