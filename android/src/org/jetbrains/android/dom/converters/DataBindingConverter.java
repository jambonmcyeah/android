/*
 * Copyright (C) 2015 The Android Open Source Project
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
package org.jetbrains.android.dom.converters;

import com.android.SdkConstants;
import com.android.ide.common.resources.DataBindingResourceType;
import com.android.tools.idea.databinding.DataBindingUtil;
import com.android.tools.idea.lang.databinding.DataBindingXmlReferenceContributor;
import com.android.tools.idea.res.DataBindingInfo;
import com.android.tools.idea.res.PsiDataBindingResourceItem;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.TextRange;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.psi.*;
import com.intellij.psi.impl.PsiJavaParserFacadeImpl;
import com.intellij.psi.impl.source.resolve.ResolveCache;
import com.intellij.psi.search.GlobalSearchScope;
import com.intellij.psi.search.SearchScope;
import com.intellij.psi.xml.XmlAttributeValue;
import com.intellij.util.ArrayUtilRt;
import com.intellij.util.IncorrectOperationException;
import com.intellij.util.xml.ConvertContext;
import com.intellij.util.xml.CustomReferenceConverter;
import com.intellij.util.xml.GenericDomValue;
import com.intellij.util.xml.ResolvingConverter;
import org.jetbrains.android.AndroidResolveScopeEnlarger;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;

import static com.android.tools.idea.lang.databinding.DataBindingCompletionUtil.JAVA_LANG;

/**
 * The converter for "type" attribute of "import" element in databinding layouts.
 */
public class DataBindingConverter extends ResolvingConverter<PsiElement> implements CustomReferenceConverter<PsiElement> {
  @Nullable
  private static String getImport(@NotNull String alias, @NotNull ConvertContext context) {
    DataBindingInfo bindingInfo = getDataBindingInfo(context);
    if (bindingInfo == null) {
      return null;
    }
    return bindingInfo.resolveImport(alias);
  }

  /**
   * Completion is handled by {@link com.android.tools.idea.lang.databinding.DataBindingCompletionContributor}. So, nothing to do here.
   */
  @Override
  @NotNull
  public Collection<? extends PsiClass> getVariants(ConvertContext context) {
    return Collections.emptyList();
  }

  @Override
  @Nullable
  public PsiElement fromString(@Nullable @NonNls String type, ConvertContext context) {
    if (type == null) {
      return null;
    }
    Module module = context.getModule();
    if (module == null) {
      return null;
    }
    DataBindingInfo dataBindingInfo = getDataBindingInfo(context);
    String qualifiedName = DataBindingUtil.resolveImport(type, dataBindingInfo);
    Project project = context.getProject();
    JavaPsiFacade psiFacade = JavaPsiFacade.getInstance(project);
    GlobalSearchScope scope = enlargeScope(module.getModuleWithDependenciesAndLibrariesScope(false),
                                           project,
                                           context.getFile());

    if (!qualifiedName.isEmpty() && qualifiedName.indexOf('.') < 0) {
      if (Character.isLowerCase(qualifiedName.charAt(0))) {
        PsiPrimitiveType primitiveType = PsiJavaParserFacadeImpl.getPrimitiveType(qualifiedName);
        if (primitiveType != null) {
          PsiClassType boxedType = primitiveType.getBoxedType(PsiManager.getInstance(project), scope);
          if (boxedType != null) {
            return boxedType.resolve();
          }
          return null;
        }
      }
      else {
        PsiClass aClass = psiFacade.findClass(JAVA_LANG + qualifiedName, scope);
        if (aClass != null) {
          return aClass;
        }
      }
    }
    return psiFacade.findClass(qualifiedName, scope);
  }

  @Override
  @Nullable
  public String toString(@Nullable PsiElement element, ConvertContext context) {
    if (element instanceof PsiClass) {
      String type = ((PsiClass)element).getQualifiedName();
      if (type != null) {
        DataBindingInfo dataBindingInfo = getDataBindingInfo(context);
        if (dataBindingInfo != null) {
          type = unresolveImport(type, dataBindingInfo);
        }
      }
      return type;
    }

    if (element instanceof PsiTypeElement) {
      return ((PsiTypeElement)element).getType().getCanonicalText();
    }
    return null;
  }

  /**
   * Replaces the fully qualified name of a class with a shorter name that can be resolved to the original name using the data binding
   * import statements.
   *
   * @param className the fully qualified class name
   * @param dataBindingInfo the data binding information containing the import statements to use
   * @return a shorter class name, or the original name if it doesn't match any import statement
   *
   * @see #resolveImport
   */
  private static String unresolveImport(String className, DataBindingInfo dataBindingInfo) {
    List<String> segments = StringUtil.split(className, ".");
    if (!segments.isEmpty()) {
      String alias = null;
      int maxMatchedSegments = 0;
      for (PsiDataBindingResourceItem psiImport : dataBindingInfo.getItems(DataBindingResourceType.IMPORT).values()) {
        String importedType = psiImport.getTypeDeclaration();
        int matchedSegments = getNumberOfMatchedSegments(importedType, segments);
        if (matchedSegments > maxMatchedSegments) {
          maxMatchedSegments = matchedSegments;
          alias = psiImport.getExtra(SdkConstants.ATTR_ALIAS);
        }
      }
      if (maxMatchedSegments != 0 && alias != null) {
        segments = segments.subList(maxMatchedSegments - 1, segments.size());
        segments.set(0, alias);
        return StringUtil.join(segments, ".");
      }
    }
    return className;
  }

  private static int getNumberOfMatchedSegments(String str, List<String> qName) {
    int offset = 0;
    for (int i = 0; i < qName.size(); i++) {
      String segment = qName.get(i);
      int endOffset = offset + segment.length();
      if (!str.startsWith(segment, offset) || (endOffset != str.length() && str.charAt(endOffset) != '.')) {
        return i;
      }
      offset = endOffset + 1;
    }
    return qName.size();
  }

  @Override
  @NotNull
  public PsiReference[] createReferences(GenericDomValue<PsiElement> value, PsiElement element, ConvertContext context) {
    assert element instanceof XmlAttributeValue;
    XmlAttributeValue attrValue = (XmlAttributeValue)element;
    String strValue = attrValue.getValue();

    List<PsiReference> result = new ArrayList<>();
    int startOffset = attrValue.getValueTextRange().getStartOffset() - attrValue.getTextRange().getStartOffset();
    createReferences(element, strValue, false, startOffset, context, result);
    return result.toArray(PsiReference.EMPTY_ARRAY);
  }

  /**
   * Creates references for the given class name and adds them to the {@code result} list.
   */
  protected static void createReferences(PsiElement element, String className, boolean resolveType, int startOffset, ConvertContext context,
                                         List<PsiReference> result) {
    Module module = context.getModule();
    if (module == null) {
      return;
    }

    int offset = startOffset;
    List<String> nameParts = StringUtil.split(className, ".");
    if (nameParts.isEmpty()) {
      return;
    }

    int idx = 0;   // For iterating over the nameParts.
    // Check if the first namePart is an alias.
    String alias = nameParts.get(idx);
    String importedType = resolveType ? getImport(alias, context) : null;
    if (importedType != null) {
      // Found an import matching the first namePart. Add a reference from this to the type.
      idx++;
      TextRange range = new TextRange(offset, offset += alias.length());
      offset++;  // Skip the next dot or dollar separator (if any)
      result.add(new AliasedReference(element, range, importedType, module));
    }
    else {
      //  Check primitives and java.lang.
      if (nameParts.size() == 1) {
        if (!alias.isEmpty()) {
          if (Character.isLowerCase(alias.charAt(0))) {
            PsiPrimitiveType primitive = PsiJavaParserFacadeImpl.getPrimitiveType(alias);
            if (primitive != null) {
              result.add(new PsiReferenceBase.Immediate<>(element, true, element));
            }
          }
          else {
            // java.lang
            PsiClass psiClass = JavaPsiFacade.getInstance(context.getProject())
                .findClass(JAVA_LANG + alias, GlobalSearchScope.moduleWithLibrariesScope(module));
            if (psiClass != null) {
              TextRange range = new TextRange(offset, offset += alias.length());
              result.add(new ClassReference(element, range, psiClass));
            }
          }
          idx++;
        }
      }
    }

    for (; idx < nameParts.size(); idx++, offset++) {
      String packageName = nameParts.get(idx);
      if (!packageName.isEmpty()) {
        TextRange range = new TextRange(offset, offset += packageName.length());
        result.add(new AliasedReference(element, range, String.join(".", nameParts.subList(0, idx + 1)), module));
      }
    }
  }

  @Nullable
  protected static DataBindingInfo getDataBindingInfo(@NotNull final ConvertContext context) {
    return DataBindingXmlReferenceContributor.getDataBindingInfo(context.getFile());
  }

  private static class AliasedReference extends PsiReferenceBase<PsiElement> {
    private final String myReferenceTo;
    private final Module myModule;

    public AliasedReference(PsiElement referenceFrom, TextRange range, String referenceTo, Module module) {
      super(referenceFrom, range, true);
      myReferenceTo = referenceTo;
      myModule = module;
    }

    @Nullable
    @Override
    public PsiElement resolve() {
      ResolveCache cache = ResolveCache.getInstance(myElement.getProject());
      return cache.resolveWithCaching(this, (psiReference, incompleteCode) -> resolveInner(), false, false);
    }

    private PsiElement resolveInner() {
      JavaPsiFacade facade = JavaPsiFacade.getInstance(myElement.getProject());
      PsiPackage aPackage = facade.findPackage(myReferenceTo);
      if (aPackage != null) {
        return aPackage;
      }

      Module module = myModule;
      GlobalSearchScope scope = module != null
                                ? module.getModuleWithDependenciesAndLibrariesScope(false)
                                : myElement.getResolveScope();

      scope = enlargeScope(scope, myElement.getProject(), myElement.getContainingFile());

      PsiClass aClass = facade.findClass(myReferenceTo, scope);
      if (aClass != null) {
        return aClass;
      }
      return null;
    }

    /**
     * Don't care about variants here since completion by
     * {@link org.jetbrains.android.AndroidCompletionContributor#completeDataBindingTypeAttr}.
     */
    @Override
    @NotNull
    public Object[] getVariants() {
      return ArrayUtilRt.EMPTY_OBJECT_ARRAY;
    }

    @Override
    @NotNull
    public String getCanonicalText() {
      return myReferenceTo;
    }

    @Override
    public PsiElement bindToElement(@NotNull PsiElement element) throws IncorrectOperationException {
      if (element instanceof PsiClass) {
        PsiClass psiClass = (PsiClass)element;
        String newName = psiClass.getQualifiedName();
        ElementManipulator<PsiElement> manipulator = ElementManipulators.getManipulator(myElement);
        if (manipulator != null) {
          return manipulator.handleContentChange(myElement, newName);
        }
      }
      return super.bindToElement(element);
    }
  }

  /**
   * Returns a new {@link GlobalSearchScope} enlarged with the right light classes (see {@link AndroidResolveScopeEnlarger}).
   *
   * <p>Usually this happens automatically, as the resolution process calls {@link PsiElement#getResolveScope()} of the {@link PsiReference}
   * element which in turn calls into {@link com.intellij.psi.impl.ResolveScopeManager} which uses the
   * {@link com.intellij.psi.ResolveScopeEnlarger} extension. Unfortunately this doesn't work in XML, because
   * {@link com.intellij.psi.xml.XmlFile} implements {@link com.intellij.psi.FileResolveScopeProvider} by returning the "all scope" and that
   * doesn't work with light R classes (because their virtual files are made up and don't belong to the project).
   *
   * @see AndroidResolveScopeEnlarger
   * @see com.intellij.psi.impl.ResolveScopeManager
   */
  private static GlobalSearchScope enlargeScope(GlobalSearchScope scope, Project project, PsiFile psiFile) {
    SearchScope lightClassesScope = new AndroidResolveScopeEnlarger().getAdditionalResolveScope(psiFile.getVirtualFile(), project);
    if (lightClassesScope != null) {
      scope = scope.union(lightClassesScope);
    }
    return scope;
  }

  private static class ClassReference extends PsiReferenceBase<PsiElement> {
    @NotNull private final PsiElement myResolveTo;

    public ClassReference(@NotNull PsiElement element, @NotNull TextRange range, @NotNull PsiElement resolveTo) {
      super(element, range, true);
      myResolveTo = resolveTo;
    }

    @Override
    @Nullable
    public PsiElement resolve() {
      ResolveCache cache = ResolveCache.getInstance(myElement.getProject());
      return cache.resolveWithCaching(this, (psiReference, incompleteCode) -> resolveInner(), false, false);
    }

    private PsiElement resolveInner() {
      return myResolveTo;
    }

    @Override
    @NotNull
    public Object[] getVariants() {
      return ArrayUtilRt.EMPTY_OBJECT_ARRAY;
    }
  }
}
