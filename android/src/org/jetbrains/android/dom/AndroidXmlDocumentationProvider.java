// Copyright 2000-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package org.jetbrains.android.dom;

import com.android.ide.common.rendering.api.AttributeFormat;
import com.android.resources.ResourceType;
import com.android.resources.ResourceUrl;
import com.android.tools.idea.databinding.DataBindingUtil;
import com.android.tools.idea.javadoc.AndroidJavaDocRenderer;
import com.android.utils.Pair;
import com.intellij.lang.Language;
import com.intellij.lang.documentation.DocumentationProvider;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleUtilCore;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.util.Ref;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.pom.PomTarget;
import com.intellij.pom.PomTargetPsiElement;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiManager;
import com.intellij.psi.impl.FakePsiElement;
import com.intellij.psi.util.*;
import com.intellij.psi.xml.XmlAttribute;
import com.intellij.psi.xml.XmlAttributeValue;
import com.intellij.psi.xml.XmlTag;
import com.intellij.psi.xml.XmlToken;
import com.intellij.reference.SoftReference;
import com.intellij.util.xml.*;
import com.intellij.util.xml.reflect.DomAttributeChildDescription;
import org.jetbrains.android.dom.attrs.AttributeDefinition;
import org.jetbrains.android.dom.attrs.AttributeDefinitions;
import org.jetbrains.android.dom.converters.AttributeValueDocumentationProvider;
import org.jetbrains.android.dom.converters.ResourceReferenceConverter;
import org.jetbrains.android.dom.wrappers.LazyValueResourceElementWrapper;
import org.jetbrains.android.facet.AndroidFacet;
import org.jetbrains.android.resourceManagers.FrameworkResourceManager;
import org.jetbrains.android.resourceManagers.ModuleResourceManagers;
import org.jetbrains.android.resourceManagers.ResourceManager;
import org.jetbrains.android.resourceManagers.ValueResourceInfo;
import org.jetbrains.android.util.AndroidResourceUtil;
import org.jetbrains.android.util.AndroidUtils;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;

import static com.android.SdkConstants.*;
import static com.intellij.psi.xml.XmlTokenType.*;

/**
 * @author Eugene.Kudelevsky
 */
public class AndroidXmlDocumentationProvider implements DocumentationProvider {
  private static final Key<SoftReference<Map<XmlName, CachedValue<String>>>> ANDROID_ATTRIBUTE_DOCUMENTATION_CACHE_KEY =
      Key.create("ANDROID_ATTRIBUTE_DOCUMENTATION_CACHE");

  @Override
  public String getQuickNavigateInfo(PsiElement element, PsiElement originalElement) {
    if (element instanceof LazyValueResourceElementWrapper) {
      ValueResourceInfo info = ((LazyValueResourceElementWrapper)element).getResourceInfo();
      return "value resource '" + info.getName() + "' [" + info.getContainingFile().getName() + "]";
    }
    return null;
  }

  @Override
  public String generateDoc(PsiElement element, @Nullable PsiElement originalElement) {
    if (element instanceof ProvidedDocumentationPsiElement) {
      return ((ProvidedDocumentationPsiElement)element).getDocumentation();
    }

    if (element instanceof LazyValueResourceElementWrapper) {
      LazyValueResourceElementWrapper wrapper = (LazyValueResourceElementWrapper)element;
      ValueResourceInfo resourceInfo = wrapper.getResourceInfo();
      ResourceType type = resourceInfo.getType();
      String name = resourceInfo.getName();

      Module module = ModuleUtilCore.findModuleForPsiElement(element);
      if (module == null) {
        return null;
      }
      AndroidFacet facet = AndroidFacet.getInstance(element);
      if (facet == null) {
        return null;
      }

      ResourceUrl url;
      ResourceUrl originalUrl = originalElement != null ? ResourceUrl.parse(originalElement.getText()) : null;
      if (originalUrl != null && name.equals(originalUrl.name)) {
        url  = originalUrl;
      } else {
        boolean isFramework = false;
        if (originalUrl != null) {
          isFramework = originalUrl.isFramework();
        } else {
          // Figure out if this resource is a framework file.
          // We really should store that info in the ValueResourceInfo instances themselves.
          // For now, attempt to figure it out
          FrameworkResourceManager frameworkResourceManager = ModuleResourceManagers.getInstance(facet).getFrameworkResourceManager();
          VirtualFile containingFile = resourceInfo.getContainingFile();
          if (frameworkResourceManager != null) {
            VirtualFile parent = containingFile.getParent();
            if (parent != null) {
              VirtualFile resDir = parent.getParent();
              if (resDir != null) {
                isFramework = frameworkResourceManager.isResourceDir(resDir);
              }
            }
          }
        }

        url = ResourceUrl.create(type, name, isFramework);
      }
      return generateDoc(element, url);
    } else if (element instanceof MyResourceElement) {
      return getResourceDocumentation(element, ((MyResourceElement)element).myResource);
    } else if (element instanceof XmlAttributeValue) {
      return getResourceDocumentation(element, ((XmlAttributeValue)element).getValue());
    }
    if (originalElement instanceof XmlToken) {
      XmlToken token = (XmlToken)originalElement;
      if (token.getTokenType() == XML_ATTRIBUTE_VALUE_START_DELIMITER) {
        PsiElement next = token.getNextSibling();
        if (next instanceof XmlToken) {
          token = (XmlToken)next;
        }
      } else if (token.getTokenType() == XML_ATTRIBUTE_VALUE_END_DELIMITER) {
        PsiElement prev = token.getPrevSibling();
        if (prev instanceof XmlToken) {
          token = (XmlToken)prev;
        }
      }
      if (token.getTokenType() == XML_ATTRIBUTE_VALUE_TOKEN) {
        String documentation = getResourceDocumentation(originalElement, token.getText());
        if (documentation != null) {
          return documentation;
        }
      } else if (token.getTokenType() == XML_DATA_CHARACTERS) {
        String text = token.getText().trim();
        String documentation = getResourceDocumentation(originalElement, text);
        if (documentation != null) {
          return documentation;
        }
      }
    }

    if (element instanceof PomTargetPsiElement && originalElement != null) {
      PomTarget target = ((PomTargetPsiElement)element).getTarget();

      if (target instanceof DomAttributeChildDescription) {
        synchronized (ANDROID_ATTRIBUTE_DOCUMENTATION_CACHE_KEY) {
          return generateDocForXmlAttribute((DomAttributeChildDescription)target, originalElement);
        }
      }
    }

    if (element instanceof MyDocElement) {
      return ((MyDocElement)element).myDocumentation;
    }
    return null;
  }

  @Nullable
  private static String getResourceDocumentation(PsiElement element, String value) {
    ResourceUrl url = ResourceUrl.parse(value);
    if (url != null) {
      return generateDoc(element, url);
    } else {
      XmlAttribute attribute = PsiTreeUtil.getParentOfType(element, XmlAttribute.class, false);
      XmlTag tag = null;
      // True if getting the documentation of the XML value (not the value of the name attribute)
      boolean isXmlValue = false;

      // If the XmlToken under the cursor is within the value of the XmlTag (XML_DATA_CHARACTERS),
      // get the XmlAttribute using the containing tag
      if (element instanceof XmlToken && XML_DATA_CHARACTERS.equals(((XmlToken)element).getTokenType())) {
        tag = PsiTreeUtil.getParentOfType(element, XmlTag.class);
        attribute = tag == null ? null : tag.getAttribute(ATTR_NAME);
        isXmlValue = true;
      }

      if (attribute == null) {
        return null;
      }

      if (ATTR_NAME.equals(attribute.getName())) {
        tag = tag != null ? tag : attribute.getParent();
        XmlTag parentTag = tag.getParentTag();
        if (parentTag == null) {
          return null;
        }

        if (TAG_RESOURCES.equals(parentTag.getName())) {
          // Handle ID definitions, http://developer.android.com/guide/topics/resources/more-resources.html#Id
          ResourceType type = AndroidResourceUtil.getResourceTypeForResourceTag(tag);
          if (type != null) {
            return generateDoc(element, type, value, false);
          }
        }
        else if (TAG_STYLE.equals(parentTag.getName())) {
          // String used to get attribute definitions
          String targetValue = value;

          if (isXmlValue && attribute.getValue() != null) {
            // In this case, the target is the name attribute of the <item> tag, which contains the key of the attr enum
            targetValue = attribute.getValue();
          }

          targetValue = StringUtil.trimStart(targetValue, ANDROID_NS_NAME_PREFIX);

          // Handle style item definitions, http://developer.android.com/guide/topics/resources/style-resource.html
          AttributeDefinition attributeDefinition = getAttributeDefinitionForElement(element, targetValue);
          if (attributeDefinition == null) {
            return null;
          }

          // Return the doc of the value if searching for an enum value, otherwise return the doc of the enum itself
          return StringUtil.trim(isXmlValue ? attributeDefinition.getValueDescription(value) : attributeDefinition.getDescription(null));
        }
      }

      // Display documentation for enum values defined in attrs.xml file, if it's present
      if (ANDROID_URI.equals(attribute.getNamespace())) {
        AttributeDefinition attributeDefinition = getAttributeDefinitionForElement(element, attribute.getLocalName());
        if (attributeDefinition == null) {
          return null;
        }
        return StringUtil.trim(attributeDefinition.getValueDescription(value));
      }
    }
    return null;
  }

  /**
   * Try to get the attribute definition for an element using first the system resource manager and then the local one.
   * Return null if can't find definition in any resource manager.
   */
  @Nullable
  private static AttributeDefinition getAttributeDefinitionForElement(@NotNull PsiElement element, @NotNull String name) {
    AndroidFacet facet = AndroidFacet.getInstance(element);
    if (facet == null) {
      return null;
    }
    AttributeDefinitions definitions = getAttributeDefinitions(ModuleResourceManagers.getInstance(facet).getFrameworkResourceManager());
    if (definitions == null) {
      return null;
    }
    AttributeDefinition attributeDefinition = definitions.getAttrDefByName(name);
    if (attributeDefinition == null) {
      // Try to get the attribute definition using the local resource manager instead
      definitions = getAttributeDefinitions(ModuleResourceManagers.getInstance(facet).getLocalResourceManager());
      if (definitions == null) {
        return null;
      }
      attributeDefinition = definitions.getAttrDefByName(name);
    }
    return attributeDefinition;
  }

  @Nullable
  private static AttributeDefinitions getAttributeDefinitions(@Nullable ResourceManager manager) {
    return manager == null ? null : manager.getAttributeDefinitions();
  }

  @Nullable
  private static String generateDocForXmlAttribute(@NotNull DomAttributeChildDescription description, @NotNull PsiElement originalElement) {
    XmlName xmlName = description.getXmlName();

    Map<XmlName, CachedValue<String>> cachedDocsMap =
        SoftReference.dereference(originalElement.getUserData(ANDROID_ATTRIBUTE_DOCUMENTATION_CACHE_KEY));

    if (cachedDocsMap != null) {
      CachedValue<String> cachedDoc = cachedDocsMap.get(xmlName);

      if (cachedDoc != null) {
        return cachedDoc.getValue();
      }
    }
    AndroidFacet facet = AndroidFacet.getInstance(originalElement);

    if (facet == null) {
      return null;
    }
    String localName = xmlName.getLocalName();
    String namespace = xmlName.getNamespaceKey();

    if (namespace == null) {
      return null;
    }
    if (AndroidUtils.NAMESPACE_KEY.equals(namespace)) {
      namespace = ANDROID_URI;
    }

    if (namespace.startsWith(URI_PREFIX) || namespace.equals(AUTO_URI)) {
      String finalNamespace = namespace;

      CachedValue<String> cachedValue = CachedValuesManager.getManager(originalElement.getProject()).createCachedValue(
        () -> {
          Pair<AttributeDefinition, String> pair = findAttributeDefinition(originalElement, facet, finalNamespace, localName);
          String doc = pair != null ? generateDocForXmlAttribute(pair.getFirst(), pair.getSecond()) : null;
          return CachedValueProvider.Result.create(doc, PsiModificationTracker.MODIFICATION_COUNT);
        }, false);
      if (cachedDocsMap == null) {
        cachedDocsMap = new HashMap<>();
        originalElement.putUserData(ANDROID_ATTRIBUTE_DOCUMENTATION_CACHE_KEY, new SoftReference<>(cachedDocsMap));
      }
      cachedDocsMap.put(xmlName, cachedValue);
      return cachedValue.getValue();
    }
    return null;
  }

  @Nullable
  private static Pair<AttributeDefinition, String> findAttributeDefinition(@NotNull PsiElement originalElement,
                                                                           @NotNull AndroidFacet facet,
                                                                           @NotNull String namespace,
                                                                           @NotNull String localName) {
    if (!originalElement.isValid()) {
      return null;
    }
    XmlTag parentTag = PsiTreeUtil.getParentOfType(originalElement, XmlTag.class);

    if (parentTag == null) {
      return null;
    }
    DomElement parentDomElement = DomManager.getDomManager(parentTag.getProject()).getDomElement(parentTag);

    if (!(parentDomElement instanceof AndroidDomElement)) {
      return null;
    }
    Ref<Pair<AttributeDefinition, String>> result = Ref.create();
    try {
      AttributeProcessingUtil.processAttributes((AndroidDomElement)parentDomElement, facet, false, (xn, attrDef, parentStyleableName) -> {
        if (xn.getLocalName().equals(localName) && namespace.equals(xn.getNamespaceKey())) {
          result.set(Pair.of(attrDef, parentStyleableName));
          throw new MyStopException();
        }
        return null;
      });
    }
    catch (MyStopException e) {
      // ignore
    }

    Pair<AttributeDefinition, String> pair = result.get();

    if (pair != null) {
      return pair;
    }
    AttributeDefinition attrDef = findAttributeDefinitionGlobally(facet, namespace, localName);
    return attrDef != null ? Pair.of(attrDef, (String)null) : null;
  }

  @Nullable
  private static AttributeDefinition findAttributeDefinitionGlobally(@NotNull AndroidFacet facet,
                                                                     @NotNull String namespace,
                                                                     @NotNull String localName) {
    ResourceManager resourceManager;
    if (ANDROID_URI.equals(namespace) || TOOLS_URI.equals(namespace)) {
      resourceManager = ModuleResourceManagers.getInstance(facet).getFrameworkResourceManager();
    }
    else if (namespace.equals(AUTO_URI) || namespace.startsWith(URI_PREFIX)) {
        resourceManager = ModuleResourceManagers.getInstance(facet).getLocalResourceManager();
    }
    else {
      resourceManager = ModuleResourceManagers.getInstance(facet).getFrameworkResourceManager();
    }

    if (resourceManager != null) {
      AttributeDefinitions attributes = resourceManager.getAttributeDefinitions();

      if (attributes != null) {
        return attributes.getAttrDefByName(localName);
      }
    }
    return null;
  }

  private static String generateDocForXmlAttribute(@NotNull AttributeDefinition definition, @Nullable String parentStyleable) {
    StringBuilder builder = new StringBuilder("<html><body>");
    Set<AttributeFormat> formats = definition.getFormats();

    if (!formats.isEmpty()) {
      builder.append("Formats: ");
      List<String> formatLabels = new ArrayList<>(formats.size());

      for (AttributeFormat format : formats) {
        formatLabels.add(StringUtil.toLowerCase(format.name()));
      }
      Collections.sort(formatLabels);

      for (int i = 0, n = formatLabels.size(); i < n; i++) {
        builder.append(formatLabels.get(i));

        if (i < n - 1) {
          builder.append(", ");
        }
      }
    }
    String[] values = definition.getValues();

    if (values.length > 0) {
      if (builder.length() > 0) {
        builder.append("<br>");
      }
      builder.append("Values: ");
      String[] sortedValues = new String[values.length];
      System.arraycopy(values, 0, sortedValues, 0, values.length);
      Arrays.sort(sortedValues);

      for (int i = 0; i < sortedValues.length; i++) {
        builder.append(sortedValues[i]);

        if (i < sortedValues.length - 1) {
          builder.append(", ");
        }
      }
    }
    // TODO(namespaces): Remove use of the deprecated method.
    String docValue = definition.getDescriptionByParentStyleableName(parentStyleable);

    if (docValue != null && !docValue.isEmpty()) {
      if (builder.length() > 0) {
        builder.append("<br><br>");
      }
      builder.append(docValue);
    }
    builder.append("</body></html>");
    return builder.toString();
  }

  @Nullable
  private static String generateDoc(PsiElement originalElement, ResourceType type, String name, boolean framework) {
    Module module = ModuleUtilCore.findModuleForPsiElement(originalElement);
    if (module == null) {
      return null;
    }

    return AndroidJavaDocRenderer.render(module, type, name, framework);
  }

  @Nullable
  private static String generateDoc(PsiElement originalElement, ResourceUrl url) {
    Module module = ModuleUtilCore.findModuleForPsiElement(originalElement);
    if (module == null) {
      return null;
    }

    return AndroidJavaDocRenderer.render(module, url);
  }

  @Override
  public PsiElement getDocumentationElementForLookupItem(@NotNull PsiManager psiManager, @NotNull Object object, @NotNull PsiElement element) {
    if (object instanceof ResourceReferenceConverter.DocumentationHolder) {
      ResourceReferenceConverter.DocumentationHolder holder = (ResourceReferenceConverter.DocumentationHolder)object;
      return new ProvidedDocumentationPsiElement(psiManager, Language.ANY, holder.getValue(), holder.getDocumentation());
    }
    if (!(element instanceof XmlAttributeValue) || !(object instanceof String)) {
      return null;
    }
    String value = (String)object;
    PsiElement parent = element.getParent();

    if (!(parent instanceof XmlAttribute)) {
      return null;
    }
    GenericAttributeValue domValue = DomManager.getDomManager(
      parent.getProject()).getDomElement((XmlAttribute)parent);

    if (domValue == null) {
      return null;
    }
    Converter converter = domValue.getConverter();

    if (converter instanceof AttributeValueDocumentationProvider) {
      String doc = ((AttributeValueDocumentationProvider)converter).getDocumentation(value);

      if (doc != null) {
        return new MyDocElement(element, doc);
      }
    }

    if ((value.startsWith(PREFIX_RESOURCE_REF) || value.startsWith(PREFIX_THEME_REF)) && !DataBindingUtil.isBindingExpression(value)) {
      return new MyResourceElement(element, value);
    }

    return null;
  }

  private static class MyDocElement extends FakePsiElement {
    final PsiElement myParent;
    final String myDocumentation;

    private MyDocElement(@NotNull PsiElement parent, @NotNull String documentation) {
      myParent = parent;
      myDocumentation = documentation;
    }

    @Override
    public PsiElement getParent() {
      return myParent;
    }
  }

  private static class MyResourceElement extends FakePsiElement {
    final PsiElement myParent;
    final String myResource;

    private MyResourceElement(@NotNull PsiElement parent, @NotNull String resource) {
      myParent = parent;
      myResource = resource;
    }

    @Override
    public PsiElement getParent() {
      return myParent;
    }
  }

  private static class MyStopException extends RuntimeException {
  }
}
