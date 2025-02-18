// Copyright 2000-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.

package org.jetbrains.android.exportSignedPackage;

import com.android.annotations.VisibleForTesting;
import com.android.builder.model.AndroidProject;
import com.android.builder.model.Variant;
import com.android.sdklib.BuildToolInfo;
import com.android.tools.idea.gradle.actions.GoToApkLocationTask;
import com.android.tools.idea.gradle.actions.GoToBundleLocationTask;
import com.android.tools.idea.gradle.project.build.invoker.GradleBuildInvoker;
import com.android.tools.idea.gradle.project.build.invoker.GradleTaskFinder;
import com.android.tools.idea.gradle.project.facet.gradle.GradleFacet;
import com.android.tools.idea.gradle.project.model.AndroidModuleModel;
import com.android.tools.idea.gradle.util.AndroidGradleSettings;
import com.google.common.base.Joiner;
import com.google.common.collect.Lists;
import com.google.wireless.android.vending.developer.signing.tools.extern.export.ExportEncryptedPrivateKeyTool;
import com.intellij.CommonBundle;
import com.intellij.ide.IdeBundle;
import com.intellij.ide.actions.RevealFileAction;
import com.intellij.ide.wizard.AbstractWizard;
import com.intellij.ide.wizard.CommitStepException;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.compiler.CompileScope;
import com.intellij.openapi.compiler.CompilerManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.externalSystem.util.ExternalSystemApiUtil;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.progress.ProgressIndicator;
import com.intellij.openapi.progress.ProgressManager;
import com.intellij.openapi.progress.Task;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.ui.GuiUtils;
import org.jetbrains.android.AndroidCommonBundle;
import org.jetbrains.android.compiler.AndroidCompileUtil;
import org.jetbrains.android.facet.AndroidFacet;
import org.jetbrains.android.sdk.AndroidPlatform;
import org.jetbrains.android.util.AndroidBundle;
import org.jetbrains.android.util.AndroidCommonUtils;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.io.File;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static com.intellij.openapi.util.text.StringUtil.capitalize;
import static com.intellij.openapi.util.text.StringUtil.decapitalize;
import static com.intellij.util.ui.UIUtil.invokeLaterIfNeeded;

/**
 * @author Eugene.Kudelevsky
 */
public class ExportSignedPackageWizard extends AbstractWizard<ExportSignedPackageWizardStep> {
  public static final String BUNDLE = "bundle";
  public static final String APK = "apk";
  private static final String ENCRYPTED_PRIVATE_KEY_FILE = "private_key.pepk";
  private static final String GOOGLE_PUBLIC_KEY =
    "eb10fe8f7c7c9df715022017b00c6471f8ba8170b13049a11e6c09ffe3056a104a3bbe4ac5a955f4ba4fe93fc8cef27558a3eb9d2a529a2092761fb833b656cd48b9de6a";

  private static Logger getLog() {
    return Logger.getInstance(ExportSignedPackageWizard.class);
  }

  @NotNull private final Project myProject;

  private AndroidFacet myFacet;
  private PrivateKey myPrivateKey;
  private X509Certificate myCertificate;

  private boolean mySigned;
  private CompileScope myCompileScope;
  private String myApkPath;
  private boolean myV1Signature;
  private boolean myV2Signature;
  private String myExportKeyPath;
  @NotNull private String myTargetType = APK;

  // build variants and gradle signing info are valid only for Gradle projects
  @NotNull private ExportEncryptedPrivateKeyTool myEncryptionTool;
  private boolean myExportPrivateKey;
  private List<String> myBuildVariants;
  private GradleSigningInfo myGradleSigningInfo;


  public ExportSignedPackageWizard(@NotNull Project project,
                                   @NotNull List<AndroidFacet> facets,
                                   boolean signed,
                                   Boolean showBundle,
                                   @NotNull ExportEncryptedPrivateKeyTool encryptionTool) {
    super(AndroidBundle.message(showBundle ? "android.export.package.wizard.bundle.title" : "android.export.package.wizard.title"),
          project);

    myProject = project;
    mySigned = signed;
    myEncryptionTool = encryptionTool;
    assert !facets.isEmpty();
    myFacet = facets.get(0);
    if (showBundle) {
      addStep(new ChooseBundleOrApkStep(this));
    }
    boolean useGradleToSign = myFacet.requiresAndroidModel();

    if (signed) {
      addStep(new KeystoreStep(this, useGradleToSign, facets));
    }

    if (useGradleToSign) {
      addStep(new GradleSignStep(this));
    }
    else {
      addStep(new ApkStep(this));
    }
    init();
  }

  public boolean isSigned() {
    return mySigned;
  }

  @Override
  protected void doOKAction() {
    if (!commitCurrentStep()) {
      return;
    }
    super.doOKAction();

    assert myFacet != null;
    if (myFacet.requiresAndroidModel()) {
      buildAndSignGradleProject();
    }
    else {
      buildAndSignIntellijProject();
    }
  }

  private void buildAndSignIntellijProject() {
    CompilerManager.getInstance(myProject).make(myCompileScope, (aborted, errors, warnings, compileContext) -> {
      if (aborted || errors != 0) {
        return;
      }

      String title = AndroidBundle.message("android.extract.package.task.title");
      ProgressManager.getInstance().run(new Task.Backgroundable(myProject, title, true, null) {
        @Override
        public void run(@NotNull ProgressIndicator indicator) {
          createAndAlignApk(myApkPath);
        }
      });
    });
  }

  private void buildAndSignGradleProject() {
    ProgressManager.getInstance().run(new Task.Backgroundable(myProject, "Generating Signed APKs", false, null) {
      @Override
      public void run(@NotNull ProgressIndicator indicator) {
        GradleFacet gradleFacet = GradleFacet.getInstance(myFacet.getModule());
        if (gradleFacet == null) {
          getLog().error("Unable to get gradle project information for module: " + myFacet.getModule().getName());
          return;
        }
        String gradleProjectPath = gradleFacet.getConfiguration().GRADLE_PROJECT_PATH;
        String rootProjectPath = ExternalSystemApiUtil.getExternalRootProjectPath(myFacet.getModule());
        if (StringUtil.isEmpty(rootProjectPath)) {
          getLog().error("Unable to get gradle root project path for module: " + myFacet.getModule().getName());
          return;
        }

        // TODO: Resolve direct AndroidGradleModel dep (b/22596984)
        AndroidModuleModel androidModel = AndroidModuleModel.get(myFacet);
        if (androidModel == null) {
          getLog().error("Unable to obtain Android project model. Did the last Gradle sync complete successfully?");
          return;
        }

        // should have been set by previous steps
        if (myBuildVariants == null) {
          getLog().error("Unable to find required information. Please check the previous steps are completed.");
          return;
        }
        List<String> gradleTasks = getGradleTasks(gradleProjectPath, androidModel, myBuildVariants, myTargetType);

        List<String> projectProperties = Lists.newArrayList();
        projectProperties.add(createProperty(AndroidProject.PROPERTY_SIGNING_STORE_FILE, myGradleSigningInfo.keyStoreFilePath));
        projectProperties.add(
          createProperty(AndroidProject.PROPERTY_SIGNING_STORE_PASSWORD, new String(myGradleSigningInfo.keyStorePassword)));
        projectProperties.add(createProperty(AndroidProject.PROPERTY_SIGNING_KEY_ALIAS, myGradleSigningInfo.keyAlias));
        projectProperties.add(createProperty(AndroidProject.PROPERTY_SIGNING_KEY_PASSWORD, new String(myGradleSigningInfo.keyPassword)));
        projectProperties.add(createProperty(AndroidProject.PROPERTY_APK_LOCATION, myApkPath));

        // These were introduced in 2.3, but gradle doesn't care if it doesn't know the properties and so they don't affect older versions.
        projectProperties.add(createProperty(AndroidProject.PROPERTY_SIGNING_V1_ENABLED, Boolean.toString(myV1Signature)));
        projectProperties.add(createProperty(AndroidProject.PROPERTY_SIGNING_V2_ENABLED, Boolean.toString(myV2Signature)));

        Map<Module, File> appModulesToOutputs = Collections.singletonMap(myFacet.getModule(), new File(myApkPath));

        assert myProject != null;

        GradleBuildInvoker gradleBuildInvoker = GradleBuildInvoker.getInstance(myProject);
        if (myTargetType.equals(BUNDLE)) {
          File exportedKeyFile = null;
          if (myExportPrivateKey) {
            exportedKeyFile = generatePrivateKeyPath();
            try {
              myEncryptionTool.run(myGradleSigningInfo.keyStoreFilePath,
                                   myGradleSigningInfo.keyAlias,
                                   GOOGLE_PUBLIC_KEY,
                                   exportedKeyFile.getPath(),
                                   myGradleSigningInfo.keyStorePassword,
                                   myGradleSigningInfo.keyPassword
              );

              final GenerateSignedApkSettings settings = GenerateSignedApkSettings.getInstance(myProject);
              //We want to only export the private key once. Anymore would be redundant.
              settings.EXPORT_PRIVATE_KEY = false;
            }
            catch (Exception e) {
              getLog().error("Something went wrong with the encryption tool", e);
              return;
            }
          }

          GoToBundleLocationTask task;
          if (exportedKeyFile != null) {
            task = new GoToBundleLocationTask(myProject, "Generate Signed Bundle", appModulesToOutputs, exportedKeyFile);
          }
          else {
            task = new GoToBundleLocationTask(myProject, "Generate Signed Bundle", appModulesToOutputs);
          }
          gradleBuildInvoker.add(task);
        }
        else {
          gradleBuildInvoker.add(new GoToApkLocationTask(appModulesToOutputs, "Generate Signed APK"));
        }
        gradleBuildInvoker.executeTasks(new File(rootProjectPath), gradleTasks, projectProperties);

        getLog().info("Export " + StringUtil.toUpperCase(myTargetType) + " command: " +
                      Joiner.on(',').join(gradleTasks) +
                      ", destination: " +
                      createProperty(AndroidProject.PROPERTY_APK_LOCATION, myApkPath));
      }

      private String createProperty(@NotNull String name, @NotNull String value) {
        return AndroidGradleSettings.createProjectProperty(name, value);
      }
    });
  }

  @VisibleForTesting
  @NotNull
  public static File getApkLocation(@NotNull String apkFolderPath, @NotNull String buildType) {
    return new File(apkFolderPath, buildType);
  }

  @VisibleForTesting
  @NotNull
  public static List<String> getGradleTasks(@NotNull String gradleProjectPath,
                                            @NotNull AndroidModuleModel androidModuleModel,
                                            @NotNull List<String> buildVariants,
                                            @NotNull String targetType) {
    List<String> gradleTasks = Lists.newArrayListWithExpectedSize(buildVariants.size());
    Variant selectedVariant = androidModuleModel.getSelectedVariant();
    String selectedTaskName = getTaskName(selectedVariant, targetType);
    String selectedVariantName = selectedVariant.getName();
    if (selectedTaskName == null) {
      getLog().warn("Could not get tasks for target " + targetType + " on variant " + selectedVariantName);
      return Collections.emptyList();
    }

    for (String variantName : buildVariants) {
      String taskName = replaceVariantFromTask(selectedTaskName, selectedVariantName, variantName);
      if (taskName != null) {
        gradleTasks.add(GradleTaskFinder.getInstance().createBuildTask(gradleProjectPath, taskName));
      }
      else {
        getLog().warn("Could not replace variant " + selectedVariantName + " with " + variantName + " for task " + selectedTaskName + ".");
      }
    }
    return gradleTasks;
  }

  @Nullable
  public static String replaceVariantFromTask(@NotNull String task, @NotNull String oldVariant, @NotNull String newVariant) {
    oldVariant = decapitalize(oldVariant);
    if (task.indexOf(oldVariant) == 1) {
      // it has the pattern ":variantName[suffix]".
      newVariant = decapitalize(newVariant);
      return task.replaceFirst(oldVariant, newVariant);
    }
    oldVariant = capitalize(oldVariant);
    if (task.contains(oldVariant)) {
      // it has the pattern ":prefixVariantName[suffix]".
      newVariant = capitalize(newVariant);
      return task.replaceFirst(oldVariant, newVariant);
    }
    // Variant name could not be found capitalized as expected.
    return null;
  }

  private static String getTaskName(Variant v, String targetType) {
    if (targetType.equals(BUNDLE)) {
      return v.getMainArtifact().getBundleTaskName();
    }
    else {
      return v.getMainArtifact().getAssembleTaskName();
    }
  }

  @Override
  protected void doNextAction() {
    if (!commitCurrentStep()) return;
    super.doNextAction();
  }

  private boolean commitCurrentStep() {
    try {
      mySteps.get(myCurrentStep).commitForNext();
    }
    catch (CommitStepException e) {
      Messages.showErrorDialog(getContentPane(), e.getMessage());
      return false;
    }
    return true;
  }

  @Override
  protected int getNextStep(int stepIndex) {
    int result = super.getNextStep(stepIndex);
    if (result != myCurrentStep) {
      mySteps.get(result).setPreviousStepIndex(myCurrentStep);
    }
    return result;
  }

  @Override
  protected int getPreviousStep(int stepIndex) {
    ExportSignedPackageWizardStep step = mySteps.get(stepIndex);
    int prevStepIndex = step.getPreviousStepIndex();
    assert prevStepIndex >= 0;
    return prevStepIndex;
  }

  @Override
  protected void updateStep() {
    int step = getCurrentStep();
    final ExportSignedPackageWizardStep currentStep = mySteps.get(step);
    getFinishButton().setEnabled(currentStep.canFinish());

    super.updateStep();

    invokeLaterIfNeeded(() -> {
      getRootPane().setDefaultButton(getNextButton());
      JComponent component = currentStep.getPreferredFocusedComponent();
      if (component != null) {
        component.requestFocus();
      }
    });
  }

  @Override
  protected String getHelpID() {
    ExportSignedPackageWizardStep step = getCurrentStepObject();
    if (step != null) {
      return step.getHelpId();
    }
    return null;
  }

  @NotNull
  public Project getProject() {
    return myProject;
  }

  public void setFacet(@NotNull AndroidFacet facet) {
    myFacet = facet;
  }

  public AndroidFacet getFacet() {
    return myFacet;
  }

  public void setPrivateKey(@NotNull PrivateKey privateKey) {
    myPrivateKey = privateKey;
  }

  public void setCertificate(@NotNull X509Certificate certificate) {
    myCertificate = certificate;
  }

  public PrivateKey getPrivateKey() {
    return myPrivateKey;
  }

  public X509Certificate getCertificate() {
    return myCertificate;
  }

  public void setCompileScope(@NotNull CompileScope compileScope) {
    myCompileScope = compileScope;
  }

  public void setApkPath(@NotNull String apkPath) {
    myApkPath = apkPath;
  }

  public void setV1Signature(boolean v1Signature) {
    myV1Signature = v1Signature;
  }

  public void setV2Signature(boolean v2Signature) {
    myV2Signature = v2Signature;
  }

  public void setGradleOptions(@NotNull List<String> buildVariants) {
    myBuildVariants = buildVariants;
  }

  public void setTargetType(@NotNull String targetType) {
    myTargetType = targetType;
  }

  @NotNull
  public String getTargetType() {
    return myTargetType;
  }

  private void createAndAlignApk(final String apkPath) {
    AndroidPlatform platform = getFacet().getConfiguration().getAndroidPlatform();
    assert platform != null;
    String sdkPath = platform.getSdkData().getLocation().getPath();
    String zipAlignPath = AndroidCommonUtils.getZipAlign(sdkPath, platform.getTarget());

    File zipalign = new File(zipAlignPath);
    if (!zipalign.isFile()) {
      BuildToolInfo buildTool = platform.getTarget().getBuildToolInfo();
      if (buildTool != null) {
        zipAlignPath = buildTool.getPath(BuildToolInfo.PathId.ZIP_ALIGN);
        zipalign = new File(zipAlignPath);
      }
    }
    final boolean runZipAlign = zipalign.isFile();
    File destFile = null;
    try {
      destFile = runZipAlign ? FileUtil.createTempFile("android", ".apk") : new File(apkPath);
      createApk(destFile);
    }
    catch (Exception e) {
      showErrorInDispatchThread(e.getMessage());
    }
    if (destFile == null) {
      return;
    }

    if (runZipAlign) {
      File realDestFile = new File(apkPath);
      String message = AndroidCommonUtils.executeZipAlign(zipAlignPath, destFile, realDestFile);
      if (message != null) {
        showErrorInDispatchThread(message);
        return;
      }
    }
    GuiUtils.invokeLaterIfNeeded(() -> {
      String title = AndroidBundle.message("android.export.package.wizard.title");
      Project project = getProject();
      File apkFile = new File(apkPath);

      VirtualFile vApkFile = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(apkFile);
      if (vApkFile != null) {
        vApkFile.refresh(true, false);
      }

      if (!runZipAlign) {
        Messages.showWarningDialog(project, AndroidCommonBundle.message(
          "android.artifact.building.cannot.find.zip.align.error"), title);
      }

      if (RevealFileAction.isSupported()) {
        if (Messages.showOkCancelDialog(project, AndroidBundle.message("android.export.package.success.message", apkFile.getName()),
                                        title, RevealFileAction.getActionName(), IdeBundle.message("action.close"),
                                        Messages.getInformationIcon()) == Messages.OK) {
          RevealFileAction.openFile(apkFile);
        }
      }
      else {
        Messages.showInfoMessage(project, AndroidBundle.message("android.export.package.success.message", apkFile), title);
      }
    }, ModalityState.defaultModalityState());
  }

  @SuppressWarnings({"IOResourceOpenedButNotSafelyClosed"})
  private void createApk(@NotNull File destFile) throws IOException, GeneralSecurityException {
    String srcApkPath = AndroidCompileUtil.getUnsignedApkPath(getFacet());
    assert srcApkPath != null;
    File srcApk = new File(FileUtil.toSystemDependentName(srcApkPath));

    if (isSigned()) {
      AndroidCommonUtils.signApk(srcApk, destFile, getPrivateKey(), getCertificate());
    }
    else {
      FileUtil.copy(srcApk, destFile);
    }
  }

  @NotNull
  private File generatePrivateKeyPath() {
    return new File(myExportKeyPath, ENCRYPTED_PRIVATE_KEY_FILE);
  }

  private void showErrorInDispatchThread(@NotNull final String message) {
    invokeLaterIfNeeded(() -> Messages.showErrorDialog(getProject(), "Error: " + message, CommonBundle.getErrorTitle()));
  }

  public void setGradleSigningInfo(GradleSigningInfo gradleSigningInfo) {
    myGradleSigningInfo = gradleSigningInfo;
  }

  public void setExportPrivateKey(boolean exportPrivateKey) {
    myExportPrivateKey = exportPrivateKey;
  }

  public void setExportKeyPath(@NotNull String exportKeyPath) {
    myExportKeyPath = exportKeyPath;
  }
}
