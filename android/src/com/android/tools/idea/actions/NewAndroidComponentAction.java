/*
 * Copyright (C) 2013 The Android Open Source Project
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
package com.android.tools.idea.actions;

import com.android.sdklib.AndroidVersion;
import com.android.tools.idea.model.AndroidModuleInfo;
import com.android.tools.idea.npw.model.ProjectSyncInvoker;
import com.android.tools.idea.npw.model.RenderTemplateModel;
import com.android.tools.idea.npw.project.AndroidPackageUtils;
import com.android.tools.idea.npw.template.ConfigureTemplateParametersStep;
import com.android.tools.idea.npw.template.TemplateHandle;
import com.android.tools.idea.projectsystem.NamedModuleTemplate;
import com.android.tools.idea.templates.TemplateManager;
import com.android.tools.idea.ui.wizard.StudioWizardDialogBuilder;
import com.android.tools.idea.wizard.model.ModelWizard;
import com.android.tools.idea.wizard.model.ModelWizardDialog;
import com.google.common.collect.ImmutableSet;
import com.intellij.openapi.actionSystem.*;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.VirtualFile;
import icons.AndroidIcons;
import org.jetbrains.android.facet.AndroidFacet;
import org.jetbrains.android.util.AndroidBundle;
import org.jetbrains.annotations.NotNull;

import java.io.File;
import java.util.List;
import java.util.Set;

import static com.android.builder.model.AndroidProject.PROJECT_TYPE_INSTANTAPP;

/**
 * An action to launch a wizard to create a component from a template.
 */
public class NewAndroidComponentAction extends AnAction {
  // These categories will be using a new wizard
  public static Set<String> NEW_WIZARD_CATEGORIES = ImmutableSet.of("Activity", "Google");

  public static final DataKey<List<File>> CREATED_FILES = DataKey.create("CreatedFiles");

  private final String myTemplateCategory;
  private final String myTemplateName;
  private final int myMinSdkApi;
  private final int myMinBuildSdkApi;
  private boolean myShouldOpenFiles = true;

  public NewAndroidComponentAction(@NotNull String templateCategory, @NotNull String templateName, int minSdkVersion) {
    this(templateCategory, templateName, minSdkVersion, minSdkVersion);
  }

  public NewAndroidComponentAction(@NotNull String templateCategory, @NotNull String templateName, int minSdkVersion, int minBuildSdkApi) {
    super(templateName, AndroidBundle.message("android.wizard.action.new.component", templateName), null);
    myTemplateCategory = templateCategory;
    myTemplateName = templateName;
    getTemplatePresentation().setIcon(isActivityTemplate() ? AndroidIcons.Activity : AndroidIcons.AndroidFile);
    myMinSdkApi = minSdkVersion;
    myMinBuildSdkApi = minBuildSdkApi;
  }

  public void setShouldOpenFiles(boolean shouldOpenFiles) {
    myShouldOpenFiles = shouldOpenFiles;
  }

  private boolean isActivityTemplate() {
    return NEW_WIZARD_CATEGORIES.contains(myTemplateCategory);
  }

  @Override
  public void update(@NotNull AnActionEvent e) {
    DataContext dataContext = e.getDataContext();
    Module module = LangDataKeys.MODULE.getData(dataContext);
    if (module == null) {
      return;
    }
    AndroidModuleInfo moduleInfo = AndroidModuleInfo.getInstance(module);
    if (moduleInfo == null) {
      return;
    }

    Presentation presentation = e.getPresentation();
    AndroidVersion buildSdkVersion = moduleInfo.getBuildSdkVersion();
    if (myMinSdkApi > moduleInfo.getMinSdkVersion().getFeatureLevel()) {
      presentation.setText(AndroidBundle.message("android.wizard.action.requires.minsdk", myTemplateName, myMinSdkApi));
      presentation.setEnabled(false);
    }
    else if (buildSdkVersion != null && myMinBuildSdkApi > buildSdkVersion.getFeatureLevel()) {
      presentation.setText(AndroidBundle.message("android.wizard.action.requires.minbuildsdk", myTemplateName, myMinBuildSdkApi));
      presentation.setEnabled(false);
    }
    else {
      final AndroidFacet facet = AndroidFacet.getInstance(module);
      boolean isProjectReady = facet != null && facet.getConfiguration().getModel() != null && facet.getConfiguration().getProjectType() != PROJECT_TYPE_INSTANTAPP;
      presentation.setEnabled(isProjectReady);
    }
  }

  @Override
  public void actionPerformed(@NotNull AnActionEvent e) {
    DataContext dataContext = e.getDataContext();

    Module module = LangDataKeys.MODULE.getData(dataContext);
    if (module == null) {
      return;
    }
    AndroidFacet facet = AndroidFacet.getInstance(module);
    if (facet == null || facet.getConfiguration().getModel() == null) {
      return;
    }

    VirtualFile targetDirectory = CommonDataKeys.VIRTUAL_FILE.getData(dataContext);
    // If the user selected a simulated folder entry (eg "Manifests"), there will be no target directory
    if (targetDirectory != null && !targetDirectory.isDirectory()) {
      targetDirectory = targetDirectory.getParent();
      assert targetDirectory != null;
    }

    File file = TemplateManager.getInstance().getTemplateFile(myTemplateCategory, myTemplateName);
    assert file != null;

    String activityDescription = e.getPresentation().getText(); // e.g. "Empty Activity", "Tabbed Activity"
    List<NamedModuleTemplate> moduleTemplates = AndroidPackageUtils.getModuleTemplates(facet, targetDirectory);
    assert !moduleTemplates.isEmpty();

    String initialPackageSuggestion = targetDirectory == null
                                      ? AndroidPackageUtils.getPackageForApplication(facet)
                                      : AndroidPackageUtils.getPackageForPath(facet, moduleTemplates, targetDirectory);
    Project project = module.getProject();

    RenderTemplateModel templateModel = new RenderTemplateModel(
      facet, new TemplateHandle(file), initialPackageSuggestion, moduleTemplates.get(0), "New " + activityDescription,
      new ProjectSyncInvoker.DefaultProjectSyncInvoker(),
      myShouldOpenFiles);

    boolean isActivity = isActivityTemplate();
    String dialogTitle = AndroidBundle.message(isActivity ? "android.wizard.new.activity.title" : "android.wizard.new.component.title");
    String stepTitle = AndroidBundle.message(isActivity ? "android.wizard.config.activity.title" : "android.wizard.config.component.title");

    ModelWizard.Builder wizardBuilder = new ModelWizard.Builder();
    wizardBuilder.addStep(new ConfigureTemplateParametersStep(templateModel, stepTitle, moduleTemplates));
    ModelWizardDialog dialog = new StudioWizardDialogBuilder(wizardBuilder.build(), dialogTitle).setProject(project).build();
    dialog.show();
    List<File> createdFiles = dataContext.getData(CREATED_FILES);
    if (createdFiles != null) {
      createdFiles.addAll(templateModel.getCreatedFiles());
    }
    /*
    // TODO: Implement the getCreatedElements call for the wizard
    final PsiElement[] createdElements = dialog.getCreatedElements();

    for (PsiElement createdElement : createdElements) {
      view.selectElement(createdElement);
    }
    */
  }
}
