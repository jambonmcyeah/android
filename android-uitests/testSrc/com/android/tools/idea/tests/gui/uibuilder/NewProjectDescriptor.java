/*
 * Copyright (C) 2018 The Android Open Source Project
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

import com.android.tools.idea.flags.StudioFlags;
import com.android.tools.idea.tests.gui.framework.GuiTestRule;
import com.android.tools.idea.tests.gui.framework.fixture.IdeFrameFixture;
import org.jetbrains.annotations.NotNull;

import static com.android.tools.idea.npw.FormFactor.MOBILE;

class NewProjectDescriptor {
  private String myActivity = "MainActivity";
  private String myPkg = "com.android.test.app";
  private String myMinSdk = "15";
  private String myName = "TestProject";
  private String myDomain = "com.android";

  protected NewProjectDescriptor(@NotNull String name) {
    withName(name);
  }

  /**
   * Set a custom package to use in the new project
   */
  NewProjectDescriptor withPackageName(@NotNull String pkg) {
    myPkg = pkg;
    return this;
  }

  /**
   * Set a new project name to use for the new project
   */
  NewProjectDescriptor withName(@NotNull String name) {
    myName = name;
    return this;
  }

  /**
   * Set a custom activity name to use in the new project
   */
  NewProjectDescriptor withActivity(@NotNull String activity) {
    myActivity = activity;
    return this;
  }

  /**
   * Set a custom minimum SDK version to use in the new project
   */
  NewProjectDescriptor withMinSdk(@NotNull String minSdk) {
    myMinSdk = minSdk;
    return this;
  }

  /**
   * Set a custom company domain to enter in the new project wizard
   */
  NewProjectDescriptor withCompanyDomain(@NotNull String domain) {
    myDomain = domain;
    return this;
  }

  /**
   * Picks brief names in order to make the test execute faster (less slow typing in name text fields)
   */
  NewProjectDescriptor withBriefNames() {
    withActivity("A").withCompanyDomain("C").withName("P").withPackageName("a.b");
    return this;
  }

  /**
   * Creates a project fixture for this description
   */
  @NotNull
  protected IdeFrameFixture create(@NotNull GuiTestRule guiTest) {
    if (StudioFlags.NPW_DYNAMIC_APPS.get()) {
      guiTest
        .welcomeFrame()
        .createNewProject()
        .clickNext()
        .getConfigureNewAndroidProjectStep()
        .enterName(myName)
        .enterPackageName(myPkg)
        .selectMinimumSdkApi(myMinSdk)
        .wizard()
        .clickFinish();
    }
    else {
        guiTest
          .welcomeFrame()
          .createNewProject()
          .getConfigureAndroidProjectStep()
          .setCppSupport(false)  // The setting is saved and restored by NewProjectModel, so can't be presumed disabled.
          .enterApplicationName(myName)
          .enterCompanyDomain(myDomain)
          .enterPackageName(myPkg)
          .wizard()
          .clickNext()
          .getConfigureFormFactorStep()
          .selectMinimumSdkApi(MOBILE, myMinSdk)
          .wizard()
          .clickNext()
          .clickNext() // Skip "Add Activity" step
          .getChooseOptionsForNewFileStep()
          .enterActivityName(myActivity)
          .wizard()
          .clickFinish();
    }

    guiTest.ideFrame().waitForGradleProjectSyncToFinish();
    return guiTest.ideFrame();
  }
}
