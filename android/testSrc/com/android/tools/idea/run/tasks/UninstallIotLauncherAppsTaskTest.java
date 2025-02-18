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
package com.android.tools.idea.run.tasks;

import com.android.ddmlib.IDevice;
import com.android.ddmlib.InstallException;
import com.android.ddmlib.IotInstallChecker;
import com.android.tools.idea.run.ConsolePrinter;
import com.android.tools.idea.run.RetryingInstaller;
import com.android.tools.idea.run.util.LaunchStatus;
import com.intellij.openapi.project.Project;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.Collections;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class UninstallIotLauncherAppsTaskTest {
  private final String PACKAGE_NAME = "com.somepackage";
  private final String OTHER_PACKAGE_NAME = "com.someotherpackage";
  @Mock private Project myProject;
  @Mock private IDevice myDevice;
  @Mock private IDevice myEmbeddedDevice;
  @Mock private LaunchStatus myLaunchStatus;
  @Mock ConsolePrinter myPrinter;
  @Mock private IotInstallChecker myChecker;
  @Mock private RetryingInstaller.Prompter myPrompt;

  @Before
  public void initMocks() throws Exception {
    MockitoAnnotations.initMocks(this);
    when(myEmbeddedDevice.supportsFeature(IDevice.HardwareFeature.EMBEDDED)).thenReturn(true);
  }

  @Test
  public void testTaskSucceedsOnNonEmbeddedHardware() throws Throwable {
    UninstallIotLauncherAppsTask task = new UninstallIotLauncherAppsTask(myProject, PACKAGE_NAME);
    assertTrue(task.perform(myDevice, myLaunchStatus, myPrinter));
  }

  @Test
  public void testTaskSucceedsIfNoIotPackageExists() throws Throwable {
    UninstallIotLauncherAppsTask task = new UninstallIotLauncherAppsTask(myProject, PACKAGE_NAME);
    assertTrue(task.perform(myEmbeddedDevice, myLaunchStatus, myPrinter));
  }

  @Test
  public void testTaskPromptsUserIfIotPackageExists() throws Throwable {
    when(myChecker.getInstalledIotLauncherApps(myEmbeddedDevice)).thenReturn(Collections.singleton(OTHER_PACKAGE_NAME));
    UninstallIotLauncherAppsTask task = new UninstallIotLauncherAppsTask(PACKAGE_NAME, myChecker, myPrompt);
    task.perform(myEmbeddedDevice, myLaunchStatus, myPrinter);
    verify(myPrompt).showQuestionPrompt(ArgumentMatchers.anyString());
  }

  @Test
  public void testTaskUninstallsPackagesIfUserSaysYesAndSucceeds() throws Throwable {
    when(myChecker.getInstalledIotLauncherApps(myEmbeddedDevice)).thenReturn(Collections.singleton(OTHER_PACKAGE_NAME));
    // Answer "Yes" to "Do you want to uninstall packages?"
    when(myPrompt.showQuestionPrompt(ArgumentMatchers.anyString())).thenReturn(true);
    UninstallIotLauncherAppsTask task = new UninstallIotLauncherAppsTask(PACKAGE_NAME, myChecker, myPrompt);
    assertTrue(task.perform(myEmbeddedDevice, myLaunchStatus, myPrinter));
    verify(myEmbeddedDevice).uninstallPackage(OTHER_PACKAGE_NAME);
  }

  @Test
  public void testTaskShowsErrorMessageAndFailsIfUninstallFailed() throws Throwable {
    when(myChecker.getInstalledIotLauncherApps(myEmbeddedDevice)).thenReturn(Collections.singleton(OTHER_PACKAGE_NAME));
    // Answer "Yes" to "Do you want to uninstall packages?"
    when(myPrompt.showQuestionPrompt(ArgumentMatchers.anyString())).thenReturn(true);
    when(myEmbeddedDevice.uninstallPackage(OTHER_PACKAGE_NAME)).thenThrow(new InstallException("Error"));
    UninstallIotLauncherAppsTask task = new UninstallIotLauncherAppsTask(PACKAGE_NAME, myChecker, myPrompt);
    assertFalse(task.perform(myEmbeddedDevice, myLaunchStatus, myPrinter));
    verify(myPrompt).showErrorMessage(ArgumentMatchers.anyString());
  }

  @Test
  public void testTaskFailsIfUserSaysNo() throws Throwable {
    when(myChecker.getInstalledIotLauncherApps(myEmbeddedDevice)).thenReturn(Collections.singleton(OTHER_PACKAGE_NAME));
    // Answer "No" to "Do you want to uninstall packages?"
    when(myPrompt.showQuestionPrompt(ArgumentMatchers.anyString())).thenReturn(false);
    UninstallIotLauncherAppsTask task = new UninstallIotLauncherAppsTask(PACKAGE_NAME, myChecker, myPrompt);
    assertFalse(task.perform(myEmbeddedDevice, myLaunchStatus, myPrinter));
  }
}
