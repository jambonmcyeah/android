// Copyright 2000-2018 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.

package org.jetbrains.android.exportSignedPackage;

import com.android.annotations.VisibleForTesting;
import com.android.tools.idea.gradle.util.DynamicAppUtils;
import com.android.tools.idea.instantapp.InstantApps;
import com.intellij.credentialStore.CredentialAttributesKt;
import com.intellij.credentialStore.Credentials;
import com.intellij.execution.configurations.GeneralCommandLine;
import com.intellij.execution.util.ExecUtil;
import com.intellij.ide.passwordSafe.PasswordSafe;
import com.intellij.ide.util.PropertiesComponent;
import com.intellij.ide.wizard.CommitStepException;
import com.intellij.openapi.fileChooser.FileChooserDescriptor;
import com.intellij.openapi.fileChooser.FileChooserDescriptorFactory;
import com.intellij.openapi.fileChooser.actions.GotoDesktopDirAction;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleType;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.TextFieldWithBrowseButton;
import com.intellij.openapi.util.SystemInfo;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.ui.CollectionComboBoxModel;
import com.intellij.ui.HyperlinkLabel;
import com.intellij.ui.SimpleListCellRenderer;
import com.intellij.ui.components.JBCheckBox;
import com.intellij.ui.components.JBLabel;
import com.intellij.util.SystemProperties;
import com.intellij.util.containers.ContainerUtil;
import org.jetbrains.android.compiler.artifact.ApkSigningSettingsForm;
import org.jetbrains.android.facet.AndroidFacet;
import org.jetbrains.android.util.AndroidBundle;
import org.jetbrains.android.util.AndroidUiUtil;
import org.jetbrains.android.util.AndroidUtils;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import javax.swing.event.HyperlinkEvent;
import javax.swing.event.HyperlinkListener;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.Arrays;
import java.util.List;

import static com.intellij.credentialStore.CredentialAttributesKt.CredentialAttributes;
import static com.intellij.openapi.ui.DialogWrapper.CANCEL_EXIT_CODE;

/**
 * @author Eugene.Kudelevsky
 */
class KeystoreStep extends ExportSignedPackageWizardStep implements ApkSigningSettingsForm {
  public static final String MODULE_PROPERTY = "ExportedModule";
  @VisibleForTesting static final String KEY_STORE_PASSWORD_KEY = "KEY_STORE_PASSWORD";
  @VisibleForTesting static final String KEY_PASSWORD_KEY = "KEY_PASSWORD";

  private static class KeyStorePasswordRequestor {
    // dummy: used as a requestor class id to access the key store password
  }

  private static class KeyPasswordRequestor {
    // dummy: used as a requestor class id to access the key password
  }

  private JPanel myContentPanel;
  private JButton myCreateKeyStoreButton;
  private JBCheckBox myExportKeysCheckBox;
  private HyperlinkLabel myGoogleAppSigningLabel;
  private JPasswordField myKeyStorePasswordField;
  private JPasswordField myKeyPasswordField;
  private TextFieldWithBrowseButton.NoPathCompletion myKeyAliasField;
  private JTextField myKeyStorePathField;
  private JButton myLoadKeyStoreButton;
  private JBCheckBox myRememberPasswordCheckBox;
  @VisibleForTesting
  JComboBox<AndroidFacet> myModuleCombo;
  private JPanel myGradlePanel;
  private HyperlinkLabel myCloseAndUpdateLink;
  private JBLabel myKeyStorePathLabel;
  private JBLabel myKeyStorePasswordLabel;
  private JBLabel myKeyAliasLabel;
  private JBLabel myKeyPasswordLabel;
  private JPanel myExportKeyPanel;
  @VisibleForTesting
  JBLabel myExportKeyPathLabel;
  @VisibleForTesting
  TextFieldWithBrowseButton myExportKeyPathField;

  private final ExportSignedPackageWizard myWizard;
  private final boolean myUseGradleForSigning;
  @VisibleForTesting
  AndroidFacet mySelection;
  @VisibleForTesting final List<AndroidFacet> myFacets;

  public KeystoreStep(@NotNull ExportSignedPackageWizard wizard,
                      boolean useGradleForSigning,
                      @NotNull List<AndroidFacet> facets) {
    myWizard = wizard;
    myFacets = facets;
    myUseGradleForSigning = useGradleForSigning;
    final Project project = wizard.getProject();

    final GenerateSignedApkSettings settings = GenerateSignedApkSettings.getInstance(project);
    myKeyStorePathField.setText(settings.KEY_STORE_PATH);
    myKeyAliasField.setText(settings.KEY_ALIAS);
    myRememberPasswordCheckBox.setSelected(settings.REMEMBER_PASSWORDS);

    if (settings.REMEMBER_PASSWORDS) {
      final String keyStorePasswordKey = makePasswordKey(KEY_STORE_PASSWORD_KEY, settings.KEY_STORE_PATH, null);
      String password = retrievePassword(KeyStorePasswordRequestor.class, keyStorePasswordKey);
      if (password != null) {
        myKeyStorePasswordField.setText(password);
      }

      final String keyPasswordKey = makePasswordKey(KEY_PASSWORD_KEY, settings.KEY_STORE_PATH, settings.KEY_ALIAS);
      password = retrievePassword(KeyPasswordRequestor.class, keyPasswordKey);
      if (password != null) {
        myKeyPasswordField.setText(password);
      }
    }

    myModuleCombo.setRenderer(SimpleListCellRenderer.create((label, value, index) -> {
      if (value == null) return;
      Module module = value.getModule();
      label.setText(module.getName());
      label.setIcon(ModuleType.get(module).getIcon());
    }));
    myCloseAndUpdateLink.setHyperlinkText(AndroidBundle.message("android.export.package.bundle.gradle.update"));
    myCloseAndUpdateLink.addHyperlinkListener(new HyperlinkListener() {
      @Override
      public void hyperlinkUpdate(HyperlinkEvent e) {
        if (DynamicAppUtils.promptUserForGradleUpdate(project)) {
          myWizard.close(CANCEL_EXIT_CODE);
        }
      }
    });
    myGradlePanel.setVisible(false);
    myModuleCombo.addActionListener(e -> updateSelection((AndroidFacet)myModuleCombo.getSelectedItem()));

    myExportKeysCheckBox.addActionListener(new ActionListener() {
      @Override
      public void actionPerformed(ActionEvent e) {
        myExportKeyPathLabel.setVisible(myExportKeysCheckBox.isSelected());
        myExportKeyPathField.setVisible(myExportKeysCheckBox.isSelected());
      }
    });
    FileChooserDescriptor descriptor = FileChooserDescriptorFactory.createSingleFolderDescriptor();
    myExportKeyPathField.addBrowseFolderListener("Select Encrypted Key Destination Folder", null, myWizard.getProject(), descriptor);
    VirtualFile desktopDir = getDesktopDirectory();
    if (desktopDir != null) {
      myExportKeyPathField.setText(desktopDir.getPath());
    }

    AndroidUiUtil.initSigningSettingsForm(project, this);
  }

  @Override
  public void _init() {
    super._init();
    boolean isBundle = myWizard.getTargetType().equals(ExportSignedPackageWizard.BUNDLE);
    updateModuleDropdown(isBundle);

    if (isBundle) {
      final GenerateSignedApkSettings settings = GenerateSignedApkSettings.getInstance(myWizard.getProject());
      myExportKeysCheckBox.setSelected(settings.EXPORT_PRIVATE_KEY);
      myGoogleAppSigningLabel.setHyperlinkText("Google Play App Signing");
      myGoogleAppSigningLabel.setHyperlinkTarget("https://support.google.com/googleplay/android-developer/answer/7384423");
      myGoogleAppSigningLabel.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
      myExportKeysCheckBox.setVisible(true);
      myGoogleAppSigningLabel.setVisible(true);
      myExportKeyPathLabel.setVisible(myExportKeysCheckBox.isVisible() && myExportKeysCheckBox.isSelected());
      myExportKeyPathField.setVisible(myExportKeysCheckBox.isVisible() && myExportKeysCheckBox.isSelected());
    }
    else {
      myExportKeysCheckBox.setVisible(false);
      myGoogleAppSigningLabel.setVisible(false);
      myExportKeyPathLabel.setVisible(false);
      myExportKeyPathField.setVisible(false);
    }
  }

  private void updateModuleDropdown(boolean isBundle) {
    List<AndroidFacet> facets = isBundle ? filteredFacets(myFacets) : myFacets;
    myModuleCombo.setEnabled(facets.size() > 1);
    if (!facets.isEmpty()) {
      if (mySelection == null) {
        String moduleName = PropertiesComponent.getInstance(myWizard.getProject()).getValue(MODULE_PROPERTY);
        if (moduleName != null) {
          for (AndroidFacet facet : facets) {
            if (moduleName.equals(facet.getModule().getName())) {
              mySelection = facet;
              break;
            }
          }
        }
      }

      // it's possible for mySelection to be filtered out if user goes from apk -> select an instant app module -> back to build a bundle
      // switch to the first valid facet in that case.
      if (!facets.contains(mySelection)) {
        mySelection = facets.get(0);
      }

      myModuleCombo.setModel(new CollectionComboBoxModel<>(facets, mySelection));
      updateSelection(mySelection);
    }
  }

  // Instant Apps cannot be built as bundles
  private static List<AndroidFacet> filteredFacets(List<AndroidFacet> facets) {
    return ContainerUtil.filter(facets, f -> !InstantApps.isInstantAppApplicationModule(f.getModule()));
  }

  private void updateSelection(@Nullable AndroidFacet selectedItem) {
    mySelection = selectedItem;
    showGradleError(!isGradleValid(myWizard.getTargetType()));
  }

  private boolean isGradleValid(@Nullable String targetType) {
    // all gradle versions are valid unless targetType is bundle
    if (!targetType.equals(ExportSignedPackageWizard.BUNDLE)) {
      return true;
    }

    if (mySelection == null) return true;
    return DynamicAppUtils.supportsBundleTask(mySelection.getModule());
  }

  private void showGradleError(boolean showError) {
    // key store fields
    myKeyStorePasswordField.setVisible(!showError);
    myKeyPasswordField.setVisible(!showError);
    myKeyAliasField.setVisible(!showError);
    myKeyStorePathField.setVisible(!showError);
    myCreateKeyStoreButton.setVisible(!showError);
    myLoadKeyStoreButton.setVisible(!showError);
    myRememberPasswordCheckBox.setVisible(!showError);
    myKeyStorePasswordLabel.setVisible(!showError);
    myKeyPasswordLabel.setVisible(!showError);
    myKeyAliasLabel.setVisible(!showError);
    myKeyStorePathLabel.setVisible(!showError);
    myExportKeyPanel.setVisible(!showError);
    myExportKeyPathLabel.setVisible(!showError);
    myExportKeyPathField.setVisible(!showError);

    // gradle error fields
    myGradlePanel.setVisible(showError);
  }

  private static String retrievePassword(@NotNull Class<?> primaryRequestor, @NotNull String key) {
    final PasswordSafe passwordSafe = PasswordSafe.getInstance();
    String password = passwordSafe.getPassword(CredentialAttributesKt.CredentialAttributes(primaryRequestor, key));
    if (password == null) {
      // Try to retrieve password previously saved with an old requestor in order to make user experience more seamless
      // while transitioning to a version which contains the fix for b/64995008, rather than having them retype all the
      // passwords at once.
      password = passwordSafe.getPassword(CredentialAttributesKt.CredentialAttributes(KeystoreStep.class, key));
    }

    return password;
  }

  private static void updateSavedPassword(@NotNull Class<?> primaryRequestor, @NotNull String key, @Nullable String value) {
    final PasswordSafe passwordSafe = PasswordSafe.getInstance();
    passwordSafe.set(CredentialAttributes(primaryRequestor, key), value == null ? null : new Credentials(key, value));
    // Always erase the one stored with the old requestor (the one used before the fix for b/64995008).
    passwordSafe.set(CredentialAttributes(KeystoreStep.class, key), null);
  }

  @VisibleForTesting
  static String makePasswordKey(@NotNull String prefix, @NotNull String keyStorePath, @Nullable String keyAlias) {
    return prefix + "__" + keyStorePath + (keyAlias != null ? "__" + keyAlias : "");
  }

  @Override
  public JComponent getPreferredFocusedComponent() {
    if (myKeyStorePathField.getText().isEmpty()) {
      return myKeyStorePathField;
    }
    else if (myKeyStorePasswordField.getPassword().length == 0) {
      return myKeyStorePasswordField;
    }
    else if (myKeyAliasField.getText().isEmpty()) {
      return myKeyAliasField;
    }
    else if (myKeyPasswordField.getPassword().length == 0) {
      return myKeyPasswordField;
    }
    return null;
  }

  @Override
  public JComponent getComponent() {
    return myContentPanel;
  }

  @Override
  public String getHelpId() {
    return "reference.android.reference.extract.signed.package.specify.keystore";
  }

  @Override
  protected void commitForNext() throws CommitStepException {
    if (!isGradleValid(myWizard.getTargetType())) {
      throw new CommitStepException(AndroidBundle.message("android.export.package.bundle.gradle.error"));
    }

    final String keyStoreLocation = myKeyStorePathField.getText().trim();
    if (keyStoreLocation.isEmpty()) {
      throw new CommitStepException(AndroidBundle.message("android.export.package.specify.keystore.location.error"));
    }

    final char[] keyStorePassword = myKeyStorePasswordField.getPassword();
    if (keyStorePassword.length == 0) {
      throw new CommitStepException(AndroidBundle.message("android.export.package.specify.key.store.password.error"));
    }

    final String keyAlias = myKeyAliasField.getText().trim();
    if (keyAlias.isEmpty()) {
      throw new CommitStepException(AndroidBundle.message("android.export.package.specify.key.alias.error"));
    }

    final char[] keyPassword = myKeyPasswordField.getPassword();
    if (keyPassword.length == 0) {
      throw new CommitStepException(AndroidBundle.message("android.export.package.specify.key.password.error"));
    }

    if (myUseGradleForSigning) {
      myWizard.setGradleSigningInfo(new GradleSigningInfo(keyStoreLocation, keyStorePassword, keyAlias, keyPassword));
    }
    else {
      final KeyStore keyStore = loadKeyStore(new File(keyStoreLocation));
      if (keyStore == null) {
        throw new CommitStepException(AndroidBundle.message("android.export.package.keystore.error.title"));
      }
      loadKeyAndSaveToWizard(keyStore, keyAlias, keyPassword);
    }

    final String keyFolder = myExportKeyPathField.getText().trim();
    if (keyFolder.isEmpty()) {
      throw new CommitStepException(AndroidBundle.message("android.apk.sign.gradle.missing.destination", myWizard.getTargetType()));
    }

    File f = new File(keyFolder);
    if (!f.isDirectory() || !f.canWrite()) {
      throw new CommitStepException(AndroidBundle.message("android.apk.sign.gradle.invalid.destination"));
    }

    final Project project = myWizard.getProject();
    final GenerateSignedApkSettings settings = GenerateSignedApkSettings.getInstance(project);

    settings.KEY_STORE_PATH = keyStoreLocation;
    settings.KEY_ALIAS = keyAlias;

    final boolean rememberPasswords = myRememberPasswordCheckBox.isSelected();
    settings.REMEMBER_PASSWORDS = rememberPasswords;

    if (myWizard.getTargetType().equals(ExportSignedPackageWizard.BUNDLE)) {
      final boolean exportPrivateKey = myExportKeysCheckBox.isSelected();
      settings.EXPORT_PRIVATE_KEY = exportPrivateKey;
      myWizard.setExportPrivateKey(exportPrivateKey);
    }

    final String keyStorePasswordKey = makePasswordKey(KEY_STORE_PASSWORD_KEY, keyStoreLocation, null);
    final String keyPasswordKey = makePasswordKey(KEY_PASSWORD_KEY, keyStoreLocation, keyAlias);

    updateSavedPassword(KeyStorePasswordRequestor.class, keyStorePasswordKey, rememberPasswords ? new String(keyStorePassword) : null);
    updateSavedPassword(KeyPasswordRequestor.class, keyPasswordKey, rememberPasswords ? new String(keyPassword) : null);

    myWizard.setFacet(getSelectedFacet());
    myWizard.setExportKeyPath(keyFolder);
  }

  private KeyStore loadKeyStore(File keystoreFile) throws CommitStepException {
    final char[] password = myKeyStorePasswordField.getPassword();
    FileInputStream fis = null;
    AndroidUtils.checkPassword(password);
    if (!keystoreFile.isFile()) {
      throw new CommitStepException(AndroidBundle.message("android.cannot.find.file.error", keystoreFile.getPath()));
    }
    final KeyStore keyStore;
    try {
      keyStore = KeyStore.getInstance(KeyStore.getDefaultType());
      //noinspection IOResourceOpenedButNotSafelyClosed
      fis = new FileInputStream(keystoreFile);
      keyStore.load(fis, password);
    }
    catch (Exception e) {
      throw new CommitStepException(e.getMessage());
    }
    finally {
      if (fis != null) {
        try {
          fis.close();
        }
        catch (IOException ignored) {
        }
      }
      Arrays.fill(password, '\0');
    }
    return keyStore;
  }

  private void loadKeyAndSaveToWizard(KeyStore keyStore, String alias, char[] keyPassword) throws CommitStepException {
    KeyStore.PrivateKeyEntry entry;
    try {
      assert keyStore != null;
      entry = (KeyStore.PrivateKeyEntry)keyStore.getEntry(alias, new KeyStore.PasswordProtection(keyPassword));
    }
    catch (Exception e) {
      throw new CommitStepException("Error: " + e.getMessage());
    }
    if (entry == null) {
      throw new CommitStepException(AndroidBundle.message("android.extract.package.cannot.find.key.error", alias));
    }
    PrivateKey privateKey = entry.getPrivateKey();
    Certificate certificate = entry.getCertificate();
    if (privateKey == null || certificate == null) {
      throw new CommitStepException(AndroidBundle.message("android.extract.package.cannot.find.key.error", alias));
    }
    myWizard.setPrivateKey(privateKey);
    myWizard.setCertificate((X509Certificate)certificate);
  }

  private AndroidFacet getSelectedFacet() {
    return (AndroidFacet)myModuleCombo.getSelectedItem();
  }

  @Override
  public JButton getLoadKeyStoreButton() {
    return myLoadKeyStoreButton;
  }

  @Override
  public JTextField getKeyStorePathField() {
    return myKeyStorePathField;
  }

  @Override
  public JPanel getPanel() {
    return myContentPanel;
  }

  @Override
  public JButton getCreateKeyStoreButton() {
    return myCreateKeyStoreButton;
  }

  @Override
  public JPasswordField getKeyStorePasswordField() {
    return myKeyStorePasswordField;
  }

  @Override
  public TextFieldWithBrowseButton getKeyAliasField() {
    return myKeyAliasField;
  }

  @Override
  public JPasswordField getKeyPasswordField() {
    return myKeyPasswordField;
  }

  @VisibleForTesting
  JBCheckBox getExportKeysCheckBox() {
    return myExportKeysCheckBox;
  }

  /** Copied from {@link GotoDesktopDirAction} **/
  @Nullable
  private static VirtualFile getDesktopDirectory() {
    File desktop = new File(SystemProperties.getUserHome(), "Desktop");

    if (!desktop.isDirectory() && SystemInfo.hasXdgOpen()) {
      String path = ExecUtil.execAndReadLine(new GeneralCommandLine("xdg-user-dir", "DESKTOP"));
      if (path != null) {
        desktop = new File(path);
      }
    }

    return desktop.isDirectory() ? LocalFileSystem.getInstance().refreshAndFindFileByIoFile(desktop) : null;
  }

}
