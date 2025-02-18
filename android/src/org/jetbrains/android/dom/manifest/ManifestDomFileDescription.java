// Copyright 2000-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package org.jetbrains.android.dom.manifest;

import com.android.tools.idea.apk.viewer.ApkFileSystem;
import com.android.xml.AndroidManifest;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleUtilCore;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.xml.XmlFile;
import com.intellij.testFramework.LightVirtualFileBase;
import com.intellij.util.xml.DomFileDescription;
import org.jetbrains.android.facet.AndroidFacet;
import org.jetbrains.android.facet.IdeaSourceProvider;
import org.jetbrains.android.util.AndroidUtils;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import static com.android.SdkConstants.FN_ANDROID_MANIFEST_XML;
import static com.android.SdkConstants.NS_RESOURCES;

/**
 * @author yole
 */
public class ManifestDomFileDescription extends DomFileDescription<Manifest> {
  public ManifestDomFileDescription() {
    super(Manifest.class, AndroidManifest.NODE_MANIFEST);
  }

  @Override
  public boolean isMyFile(@NotNull XmlFile file, @Nullable Module module) {
    return (module == null) ? isManifestFile(file) : isManifestFile(file, module);
  }

  public static boolean isManifestFile(@NotNull XmlFile file) {
    final Module module = ModuleUtilCore.findModuleForPsiElement(file);
    return isManifestFile(file, module);
  }

  public static boolean isManifestFile(@NotNull XmlFile file, @Nullable Module module) {
    if (module != null && !module.isDisposed()) {
      AndroidFacet facet = AndroidFacet.getInstance(module);
      if (facet != null) {
        return isManifestFile(file, facet);
      }
    }

    VirtualFile virtualFile = file.getVirtualFile();
    // happens while indexing
    if (virtualFile == null) {
      return false;
    }

    // ignore files coming out of an APK, or manually constructed using a LightVirtualFile
    if (virtualFile.getFileSystem() instanceof ApkFileSystem || virtualFile instanceof LightVirtualFileBase) {
      return false;
    }

    return file.getName().equals(FN_ANDROID_MANIFEST_XML);
  }

  public static boolean isManifestFile(@NotNull XmlFile file, @NotNull AndroidFacet facet) {
    return file.getName().equals(FN_ANDROID_MANIFEST_XML) ||
           facet.requiresAndroidModel() && file.getVirtualFile() != null && IdeaSourceProvider.isManifestFile(facet, file.getVirtualFile());
  }

  @Override
  protected void initializeFileDescription() {
    registerNamespacePolicy(AndroidUtils.NAMESPACE_KEY, NS_RESOURCES);
  }
}
