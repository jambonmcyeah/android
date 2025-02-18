// Copyright 2000-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package org.jetbrains.android.exportSignedPackage;

import com.android.jarutils.DebugKeyProvider;
import com.android.jarutils.KeystoreHelper;
import com.intellij.ide.wizard.CommitStepException;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.Messages;
import com.intellij.openapi.util.text.StringUtil;
import org.jetbrains.android.util.AndroidBundle;
import org.jetbrains.android.util.AndroidUtils;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * @author Eugene.Kudelevsky
 */
public abstract class NewKeyForm {
  private static final Logger LOG = Logger.getInstance("#org.jetbrains.android.exportSignedPackage.NewKeyForm");

  private JPanel myContentPanel;
  private JTextField myAliasField;
  private JPasswordField myKeyPasswordField;
  private JPasswordField myConfirmKeyPasswordField;
  private JSpinner myValiditySpinner;
  private JTextField myFirstAndLastNameField;
  private JTextField myOrganizationUnitField;
  private JTextField myCityField;
  private JTextField myStateOrProvinceField;
  private JTextField myCountryCodeField;
  private JPanel myCertificatePanel;
  private JTextField myOrganizationField;

  private KeyStore myKeyStore;
  private PrivateKey myPrivateKey;
  private X509Certificate myCertificate;

  public NewKeyForm() {
    myValiditySpinner.setModel(new SpinnerNumberModel(25, 1, 1000, 1));
  }

  private int getValidity() {
    SpinnerNumberModel model = (SpinnerNumberModel)myValiditySpinner.getModel();
    return model.getNumber().intValue();
  }

  public void init() {
    myAliasField.setText(generateAlias());

    myKeyPasswordField.setName("myKeyPasswordField");
    myConfirmKeyPasswordField.setName("myConfirmKeyPasswordField");
  }

  public JPanel getContentPanel() {
    return myContentPanel;
  }

  private boolean findNonEmptyCertificateField() {
    for (Component component : myCertificatePanel.getComponents()) {
      if (component instanceof JTextField) {
        if (!((JTextField)component).getText().trim().isEmpty()) {
          return true;
        }
      }
    }
    return false;
  }

  public void createKey() throws CommitStepException {
    if (getKeyAlias().isEmpty()) {
      throw new CommitStepException(AndroidBundle.message("android.export.package.specify.key.alias.error"));
    }
    AndroidUtils.checkNewPassword(myKeyPasswordField, myConfirmKeyPasswordField);
    if (!findNonEmptyCertificateField()) {
      throw new CommitStepException(AndroidBundle.message("android.export.package.specify.certificate.field.error"));
    }
    doCreateKey();
  }

  @NotNull
  private String generateAlias() {
    List<String> aliasList = getExistingKeyAliasList();
    String prefix = "key";
    if (aliasList == null) {
      return prefix + '0';
    }
    Set<String> aliasSet = new HashSet<>();
    for (String alias : aliasList) {
      aliasSet.add(StringUtil.toLowerCase(alias));
    }
    for (int i = 0; ; i++) {
      String alias = prefix + i;
      if (!aliasSet.contains(alias)) {
        return alias;
      }
    }
  }

  @Nullable
  protected abstract List<String> getExistingKeyAliasList();

  private static void buildDName(StringBuilder builder, String prefix, JTextField textField) {
    if (textField != null) {
      String value = textField.getText().trim();
      if (!value.isEmpty()) {
        if (builder.length() > 0) {
          builder.append(",");
        }
        builder.append(prefix);
        builder.append('=');
        builder.append(value);
      }
    }
  }

  private String getDName() {
    StringBuilder builder = new StringBuilder();
    buildDName(builder, "CN", myFirstAndLastNameField);
    buildDName(builder, "OU", myOrganizationUnitField);
    buildDName(builder, "O", myOrganizationField);
    buildDName(builder, "L", myCityField);
    buildDName(builder, "ST", myStateOrProvinceField);
    buildDName(builder, "C", myCountryCodeField);
    return builder.toString();
  }



  private void doCreateKey() throws CommitStepException {
    String keystoreLocation = getKeyStoreLocation();
    String keystorePassword = new String(getKeyStorePassword());
    String keyPassword = new String(getKeyPassword());
    String keyAlias = getKeyAlias();
    String dname = getDName();
    assert dname != null;

    if (keystorePassword.indexOf('"') >= 0 || keyPassword.indexOf('"') >= 0) {
      throw new CommitStepException("Passwords cannot contain quote character");
    }

    boolean createdStore = false;
    final StringBuilder errorBuilder = new StringBuilder();
    final StringBuilder outBuilder = new StringBuilder();
    try {
      createdStore = KeystoreHelper
        .createNewStore(keystoreLocation, null, keystorePassword, keyAlias, keyPassword, dname, getValidity(),
                        new DebugKeyProvider.IKeyGenOutput() {
                          @Override
                          public void err(String message) {
                            errorBuilder.append(message).append('\n');
                            LOG.info("Error: " + message);
                          }

                          @Override
                          public void out(String message) {
                            outBuilder.append(message).append('\n');
                            LOG.info(message);
                          }
                        });
    }
    catch (Exception e) {
      LOG.info(e);
      errorBuilder.append(e.getMessage()).append('\n');
    }
    normalizeBuilder(errorBuilder);
    normalizeBuilder(outBuilder);

    if (createdStore) {
      if (errorBuilder.length() > 0) {
        String prefix = AndroidBundle.message("android.create.new.key.error.prefix");
        Messages.showErrorDialog(myContentPanel, prefix + '\n' + errorBuilder.toString());
      }
    }
    else {
      if (errorBuilder.length() > 0) {
        throw new CommitStepException(errorBuilder.toString());
      }
      if (outBuilder.length() > 0) {
        throw new CommitStepException(outBuilder.toString());
      }
      throw new CommitStepException(AndroidBundle.message("android.cannot.create.new.key.error"));
    }
    loadKeystoreAndKey(keystoreLocation, keystorePassword, keyAlias, keyPassword);
  }

  @NotNull
  public char[] getKeyPassword() {
    return myKeyPasswordField.getPassword();
  }

  @NotNull
  public String getKeyAlias() {
    return myAliasField.getText().trim();
  }

  @NotNull
  protected abstract Project getProject();

  @NotNull
  protected abstract char[] getKeyStorePassword();

  @NotNull
  protected abstract String getKeyStoreLocation();

  private void loadKeystoreAndKey(String keystoreLocation, String keystorePassword, String keyAlias, String keyPassword)
    throws CommitStepException {
    FileInputStream fis = null;
    try {
      KeyStore keyStore = KeyStore.getInstance(KeyStore.getDefaultType());
      fis = new FileInputStream(new File(keystoreLocation));
      keyStore.load(fis, keystorePassword.toCharArray());
      myKeyStore = keyStore;
      KeyStore.PrivateKeyEntry entry = (KeyStore.PrivateKeyEntry)keyStore.getEntry(
        keyAlias, new KeyStore.PasswordProtection(keyPassword.toCharArray()));
      if (entry == null) {
        throw new CommitStepException(AndroidBundle.message("android.extract.package.cannot.find.key.error", keyAlias));
      }
      PrivateKey privateKey = entry.getPrivateKey();
      Certificate certificate = entry.getCertificate();
      if (privateKey == null || certificate == null) {
        throw new CommitStepException(AndroidBundle.message("android.extract.package.cannot.find.key.error", keyAlias));
      }
      myPrivateKey = privateKey;
      myCertificate = (X509Certificate)certificate;
    }
    catch (Exception e) {
      throw new CommitStepException("Error: " + e.getMessage());
    }
    finally {
      if (fis != null) {
        try {
          fis.close();
        }
        catch (IOException ignored) {
        }
      }
    }
  }

  private static void normalizeBuilder(StringBuilder builder) {
    if (builder.length() > 0) {
      builder.deleteCharAt(builder.length() - 1);
    }
  }

  @Nullable
  public KeyStore getKeyStore() {
    return myKeyStore;
  }

  @Nullable
  public PrivateKey getPrivateKey() {
    return myPrivateKey;
  }

  @Nullable
  public X509Certificate getCertificate() {
    return myCertificate;
  }
}
