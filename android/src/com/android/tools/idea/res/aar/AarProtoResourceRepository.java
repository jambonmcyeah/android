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
package com.android.tools.idea.res.aar;

import com.android.SdkConstants;
import com.android.aapt.ConfigurationOuterClass.Configuration;
import com.android.aapt.Resources;
import com.android.ide.common.rendering.api.*;
import com.android.ide.common.resources.ResourceItem;
import com.android.ide.common.resources.configuration.DensityQualifier;
import com.android.ide.common.resources.configuration.FolderConfiguration;
import com.android.ide.common.util.PathString;
import com.android.resources.Density;
import com.android.resources.ResourceType;
import com.android.resources.ResourceVisibility;
import com.android.tools.idea.res.ResourceHelper;
import com.android.utils.XmlUtils;
import com.google.common.base.CharMatcher;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ListMultimap;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.util.BitUtil;
import com.intellij.util.io.URLUtil;
import org.jetbrains.android.dom.manifest.AndroidManifestUtils;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.*;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Repository of resources defined in an AAR file where resources are stored in protocol buffer format.
 * See https://developer.android.com/studio/projects/android-library.html.
 * See https://android.googlesource.com/platform/frameworks/base/+/master/tools/aapt2/Resources.proto
 */
public class AarProtoResourceRepository extends AarSourceResourceRepository {
  private static final Logger LOG = Logger.getInstance(AarProtoResourceRepository.class);
  /** The name of the res.apk ZIP entry containing value resources. */
  private static final String RESOURCE_TABLE_ENTRY = "resources.pb";

  // The following constants represent the complex dimension encoding defined in
  // https://android.googlesource.com/platform/frameworks/base/+/master/libs/androidfw/include/androidfw/ResourceTypes.h
  private static final int COMPLEX_UNIT_MASK = 0xF;
  private static final String[] DIMEN_SUFFIXES = {"px", "dp", "sp", "pt", "in", "mm"};
  private static final String[] FRACTION_SUFFIXES = {"%", "%p"};
  private static final int COMPLEX_RADIX_SHIFT = 4;
  private static final int COMPLEX_RADIX_MASK = 0x3;
  /** Multiplication factors for 4 possible radixes. */
  private static final double[] RADIX_FACTORS = {1., 1. / (1 << 7), 1. / (1 << 15), 1. / (1 << 23)};
  // The signed mantissa is stored in the higher 24 bits of the value.
  private static final int COMPLEX_MANTISSA_SHIFT = 8;

  @NotNull private final File myResApkFileOrFolder;
  /**
   * Either "apk" or "file"" depending on whether the repository was loaded from res.apk
   * or its unzipped contents.
   */
  private String myFilesystemProtocol;
  /**
   * Common prefix of paths of all file resources. Used to compose resource path strings when
   * the repository is loaded from unzipped contents of res.apk.
   */
  private String myResourcePathPrefix;
  /**
   * Common prefix of URLs of all file resources. Used to compose resource URLs returned by
   * the {@link AarFileResourceItem#getValue()} method.
   */
  private String myResourceUrlPrefix;
  private ResourceUrlParser myUrlParser;

  private AarProtoResourceRepository(@NotNull File apkFileOrFolder, @NotNull ResourceNamespace namespace, @Nullable String libraryName) {
    super(apkFileOrFolder.getParentFile(), namespace, libraryName);
    myResApkFileOrFolder = apkFileOrFolder;
  }

  /**
   * Creates a resource repository for an AAR file.
   *
   * @param apkFileOrFolder the res.apk file or a folder with its unzipped contents
   * @param libraryName the name of the library
   * @return the created resource repository, or null if {@code aarFolder} does not contain either "res.apk" or "resources.pb"
   */
  @NotNull
  public static AarProtoResourceRepository createProtoRepository(@NotNull File apkFileOrFolder, @Nullable String libraryName) {
    DataLoader loader = new DataLoader(apkFileOrFolder);
    try {
      loader.load();
    } catch (IOException e) {
      LOG.error(e);
      return new AarProtoResourceRepository(apkFileOrFolder, getNamespace(loader.packageName), libraryName); // Return an empty repository.
    }

    AarProtoResourceRepository repository = new AarProtoResourceRepository(apkFileOrFolder, getNamespace(loader.packageName), libraryName);
    repository.load(loader);
    return repository;
  }

  @NotNull
  private static ResourceNamespace getNamespace(@Nullable String packageName) {
    return packageName == null ? ResourceNamespace.RES_AUTO : ResourceNamespace.fromPackageName(packageName);
  }

  private void load(@NotNull DataLoader loader) {
    loadResourceTable(loader.resourceTableMsg);
    if (loader.loadedFromResApk) {
      myFilesystemProtocol = "apk";
      myResourcePathPrefix = myResApkFileOrFolder.getPath() + URLUtil.JAR_SEPARATOR;
    } else {
      myFilesystemProtocol = "file";
      myResourcePathPrefix = myResApkFileOrFolder.getAbsolutePath() + File.separator;
    }
    myResourceUrlPrefix = myFilesystemProtocol + "://" + myResourcePathPrefix;
  }

  private void loadResourceTable(@NotNull Resources.ResourceTable resourceTableMsg) {
    Map<Configuration, AarConfiguration> configCache = new HashMap<>();
    myUrlParser = new ResourceUrlParser();
    try  {
      for (Resources.Package packageMsg : resourceTableMsg.getPackageList()) {
        for (Resources.Type typeMsg : packageMsg.getTypeList()) {
          ResourceType resourceType = ResourceType.fromClassName(typeMsg.getName());
          if (resourceType == null) {
            LOG.warn("Unexpected resource type: " + typeMsg.getName());
            continue;
          }
          for (Resources.Entry entryMsg : typeMsg.getEntryList()) {
            String resourceName = entryMsg.getName();
            Resources.Visibility visibilityMsg = entryMsg.getVisibility();
            ResourceVisibility visibility = computeVisibility(visibilityMsg);
            for (Resources.ConfigValue configValueMsg : entryMsg.getConfigValueList()) {
              AarConfiguration configuration = getConfiguration(configValueMsg.getConfig(), configCache);
              Resources.Value valueMsg = configValueMsg.getValue();
              ResourceItem item = createResourceItem(valueMsg, resourceType, resourceName, configuration, visibility);
              if (item != null) {
                addResourceItem(item);
              }
            }
          }
        }
      }
    } finally {
      myUrlParser = null; // Not needed anymore.
    }
  }

  @Nullable
  final PathString getPathString(@Nullable String relativeResourcePath) {
    return relativeResourcePath == null ? null : new PathString(myFilesystemProtocol, myResourcePathPrefix + relativeResourcePath);
  }

  /**
   * Produces a string to be returned by the {@link AarFileResourceItem#getValue()} method.
   * The string represents an URL in one of the following formats:
   * <ul>
   *  <li>file URL, e.g. "file:///foo/bar/res/layout/my_layout.xml"</li>
   *  <li>URL of a zipped element inside the res.apk file, e.g. "apk:/foo/bar/res.apk!/res/layout/my_layout.xml"</li>
   * </ul>
   *
   * @param relativeResourcePath the relative path of a file resource
   * @return the URL pointing to the file resource
   * @see ResourceHelper#toFileResourcePathString(String)
   */
  @Nullable
  final String getResourceUrl(@Nullable String relativeResourcePath) {
    return relativeResourcePath == null ? null : myResourceUrlPrefix + relativeResourcePath;
  }

  @Nullable
  private AarResourceItem createResourceItem(@NotNull Resources.Value valueMsg, @NotNull ResourceType resourceType,
                                             @NotNull String resourceName, @NotNull AarConfiguration configuration,
                                             @NotNull ResourceVisibility visibility) {
    switch (valueMsg.getValueCase()) {
      case ITEM:
        return createResourceItem(valueMsg.getItem(), resourceType, resourceName, configuration, visibility);

      case COMPOUND_VALUE:
        String description = valueMsg.getComment();
        if (CharMatcher.whitespace().matchesAllOf(description)) {
          description = null;
        }
        return createResourceItem(valueMsg.getCompoundValue(), resourceName, configuration, visibility, description);

      case VALUE_NOT_SET:
      default:
        LOG.warn("Unexpected Value message: " + valueMsg);
        break;
    }
    return null;
  }

  @Nullable
  private AarResourceItem createResourceItem(@NotNull Resources.Item itemMsg, @NotNull ResourceType resourceType,
                                             @NotNull String resourceName, @NotNull AarConfiguration configuration,
                                             @NotNull ResourceVisibility visibility) {
    switch (itemMsg.getValueCase()) {
      case FILE: {
        String path = itemMsg.getFile().getPath();
        FolderConfiguration config = configuration.getFolderConfiguration();
        DensityQualifier densityQualifier = config.getDensityQualifier();
        if (densityQualifier != null) {
          Density densityValue = densityQualifier.getValue();
          if (densityValue != null) {
            return new AarDensityBasedFileResourceItem(resourceType, resourceName, configuration, visibility, path, densityValue);
          }
        }
        return new AarFileResourceItem(resourceType, resourceName, configuration, visibility, path);
      }

      case REF: {
        String ref = decode(itemMsg.getRef());
        return createResourceItem(resourceType, resourceName, configuration, visibility, ref);
      }

      case STR: {
        String textValue = itemMsg.getStr().getValue();
        return new AarTextValueResourceItem(resourceType, resourceName, configuration, visibility, textValue, null);
      }

      case RAW_STR: {
        String str = itemMsg.getRawStr().getValue();
        return createResourceItem(resourceType, resourceName, configuration, visibility, str);
      }

      case PRIM: {
        String str = decode(itemMsg.getPrim());
        return createResourceItem(resourceType, resourceName, configuration, visibility, str);
      }

      case STYLED_STR: {
        Resources.StyledString styledStrMsg = itemMsg.getStyledStr();
        String textValue = styledStrMsg.getValue();
        String rawXmlValue = ProtoStyledStringDecoder.getRawXmlValue(styledStrMsg);
        return new AarTextValueResourceItem(resourceType, resourceName, configuration, visibility, textValue, rawXmlValue);
      }

      case ID: {
        return createResourceItem(resourceType, resourceName, configuration, visibility, "");
      }

      case VALUE_NOT_SET:
      default:
        LOG.warn("Unexpected Item message: " + itemMsg);
        break;
    }
    return null;
  }

  @NotNull
  private static AarResourceItem createResourceItem(@NotNull ResourceType resourceType, @NotNull String resourceName,
                                                    @NotNull AarConfiguration configuration, @NotNull ResourceVisibility visibility,
                                                    @Nullable String value) {
    return new AarValueResourceItem(resourceType, resourceName, configuration, visibility, value);
  }

  @Nullable
  private AarResourceItem createResourceItem(@NotNull Resources.CompoundValue compoundValueMsg, @NotNull String resourceName,
                                             @NotNull AarConfiguration configuration, @NotNull ResourceVisibility visibility,
                                             @Nullable String description) {
    switch (compoundValueMsg.getValueCase()) {
      case ATTR:
        return createAttr(compoundValueMsg.getAttr(), resourceName, configuration, visibility, description);

      case STYLE:
        return createStyle(compoundValueMsg.getStyle(), resourceName, configuration, visibility);

      case STYLEABLE:
        return createStyleable(compoundValueMsg.getStyleable(), resourceName, configuration, visibility);

      case ARRAY:
        return createArray(compoundValueMsg.getArray(), resourceName, configuration, visibility);

      case PLURAL:
        return createPlurals(compoundValueMsg.getPlural(), resourceName, configuration, visibility);

      case VALUE_NOT_SET:
      default:
        LOG.warn("Unexpected CompoundValue message: " + compoundValueMsg);
        return null;
    }
  }

  @Nullable
  private static AarAttrResourceItem createAttr(@NotNull Resources.Attribute attributeMsg, @NotNull String resourceName,
                                                @NotNull AarConfiguration configuration, @NotNull ResourceVisibility visibility,
                                                @Nullable String description) {
    Set<AttributeFormat> formats = decodeFormatFlags(attributeMsg.getFormatFlags());

    ImmutableMap.Builder<String, Integer> valueMap = ImmutableMap.builder();
    ImmutableMap.Builder<String, String> valueDescriptionMap = ImmutableMap.builder();
    List<Resources.Attribute.Symbol> symbolList = attributeMsg.getSymbolList();
    if (symbolList.isEmpty() && attributeMsg.getFormatFlags() == Resources.Attribute.FormatFlags.ANY.getNumber()) {
      return null;
    }
    for (Resources.Attribute.Symbol symbolMsg : symbolList) {
      String name = symbolMsg.getName().getName();
      // Remove the explicit resource type to match the behavior of AarSourceResourceRepository.
      int slashPos = name.lastIndexOf('/');
      if (slashPos >= 0) {
        name = name.substring(slashPos + 1);
      }
      String symbolDescription = symbolMsg.getComment();
      if (CharMatcher.whitespace().matchesAllOf(symbolDescription)) {
        symbolDescription = null;
      }
      valueMap.put(name, symbolMsg.getValue());
      if (symbolDescription != null) {
        valueDescriptionMap.put(name, symbolDescription);
      }
    }

    return new AarAttrResourceItem(resourceName, configuration, visibility, description, formats, valueMap.build(),
                                   valueDescriptionMap.build());
  }

  @NotNull
  private AarStyleResourceItem createStyle(@NotNull Resources.Style styleMsg, @NotNull String resourceName,
                                           @NotNull AarConfiguration configuration, @NotNull ResourceVisibility visibility) {
    String parentStyle = styleMsg.getParent().getName();
    List<StyleItemResourceValue> styleItems = new ArrayList<>(styleMsg.getEntryCount());
    for (Resources.Style.Entry entryMsg : styleMsg.getEntryList()) {
      String url = entryMsg.getKey().getName();
      myUrlParser.parseResourceUrl(url);
      String name = myUrlParser.withoutType();
      String value = decode(entryMsg.getItem());
      StyleItemResourceValueImpl itemValue = new StyleItemResourceValueImpl(getNamespace(), name, value, getLibraryName());
      styleItems.add(itemValue);
    }

    return new AarStyleResourceItem(resourceName, configuration, visibility, parentStyle, styleItems);
  }

  @NotNull
  private AarStyleableResourceItem createStyleable(@NotNull Resources.Styleable styleableMsg, @NotNull String resourceName,
                                                   @NotNull AarConfiguration configuration, @NotNull ResourceVisibility visibility) {
    List<AttrResourceValue> attrs = new ArrayList<>(styleableMsg.getEntryCount());
    for (Resources.Styleable.Entry entryMsg : styleableMsg.getEntryList()) {
      String url = entryMsg.getAttr().getName();
      myUrlParser.parseResourceUrl(url);
      String packageName = myUrlParser.getPackageName();
      ResourceNamespace namespace = packageName == null ? getNamespace() : ResourceNamespace.fromPackageName(packageName);
      AttrResourceValue attrValue = new AttrResourceValueImpl(namespace, ResourceType.ATTR, myUrlParser.getName(), getLibraryName());
      attrs.add(attrValue);
    }
    return new AarStyleableResourceItem(resourceName, configuration, visibility, attrs);
  }

  @NotNull
  private AarArrayResourceItem createArray(@NotNull Resources.Array arrayMsg, @NotNull String resourceName,
                                           @NotNull AarConfiguration configuration, @NotNull ResourceVisibility visibility) {
    List<String> elements = new ArrayList<>(arrayMsg.getElementCount());
    for (Resources.Array.Element elementMsg : arrayMsg.getElementList()) {
      String text = decode(elementMsg.getItem());
      if (text != null) {
        elements.add(text);
      }
    }
    return new AarArrayResourceItem(resourceName, configuration, visibility, elements);
  }

  @NotNull
  private AarPluralsResourceItem createPlurals(@NotNull Resources.Plural pluralMsg, @NotNull String resourceName,
                                               @NotNull AarConfiguration configuration, @NotNull ResourceVisibility visibility) {
    List<String> quantities = new ArrayList<>(pluralMsg.getEntryCount());
    List<String> values = new ArrayList<>(pluralMsg.getEntryCount());
    for (Resources.Plural.Entry entryMsg : pluralMsg.getEntryList()) {
      quantities.add(getQuantity(entryMsg.getArity()));
      values.add(decode(entryMsg.getItem()));
    }
    return new AarPluralsResourceItem(resourceName, configuration, visibility, quantities, values);
  }

  @NotNull
  private static String getQuantity(@NotNull Resources.Plural.Arity arity) {
    switch (arity) {
      case ZERO:
        return "zero";
      case ONE:
        return "one";
      case TWO:
        return "two";
      case FEW:
        return "few";
      case MANY:
        return "many";
      case OTHER:
      default:
        return "other";
    }
  }

  @NotNull
  private static ResourceVisibility computeVisibility(@NotNull Resources.Visibility visibilityMsg) {
    switch (visibilityMsg.getLevel()) {
      case UNKNOWN:
        return ResourceVisibility.PRIVATE_XML_ONLY;
      case PRIVATE:
        return ResourceVisibility.PRIVATE;
      case PUBLIC:
        return ResourceVisibility.PUBLIC;
      case UNRECOGNIZED:
      default:
        return ResourceVisibility.UNDEFINED;
    }
  }

  private void addResourceItem(@NotNull ResourceItem item) {
    ListMultimap<String, ResourceItem> multimap = getFullTable().getOrPutEmpty(getNamespace(), item.getType());
    multimap.put(item.getName(), item);
  }

  @Nullable
  private String decode(@NotNull Resources.Item itemMsg) {
    switch (itemMsg.getValueCase()) {
      case REF:
        return decode(itemMsg.getRef());
      case STR:
        return itemMsg.getStr().getValue();
      case RAW_STR:
        return itemMsg.getRawStr().getValue();
      case STYLED_STR:
        return itemMsg.getStyledStr().getValue();
      case FILE:
        return itemMsg.getFile().getPath();
      case ID:
        return null;
      case PRIM:
        return decode(itemMsg.getPrim());
      case VALUE_NOT_SET:
      default:
        break;
    }
    return null;
  }

  @NotNull
  private String decode(@NotNull Resources.Reference referenceMsg) {
    String name = referenceMsg.getName();
    if (name.isEmpty()) {
      return "@null";
    }
    if (referenceMsg.getType() == Resources.Reference.Type.ATTRIBUTE) {
      myUrlParser.parseResourceUrl(name);
      if (myUrlParser.isType(ResourceType.ATTR.getName())) {
        name = myUrlParser.withoutType();
      }
      return '?' + name;
    }
    return '@' + name;
  }

  @Nullable
  private static String decode(@NotNull Resources.Primitive primitiveMsg) {
    switch (primitiveMsg.getOneofValueCase()) {
      case NULL_VALUE:
        return null;

      case EMPTY_VALUE:
        return "";

      case FLOAT_VALUE:
        return XmlUtils.trimInsignificantZeros(Float.toString(primitiveMsg.getFloatValue()));

      case DIMENSION_VALUE:
        return decodeComplexDimensionValue(primitiveMsg.getDimensionValue(), 1., DIMEN_SUFFIXES);

      case FRACTION_VALUE:
        return decodeComplexDimensionValue(primitiveMsg.getFractionValue(), 100., FRACTION_SUFFIXES);

      case INT_DECIMAL_VALUE:
        return Integer.toString(primitiveMsg.getIntDecimalValue());

      case INT_HEXADECIMAL_VALUE:
        return String.format("0x%X", primitiveMsg.getIntHexadecimalValue());

      case BOOLEAN_VALUE:
        return Boolean.toString(primitiveMsg.getBooleanValue());

      case COLOR_ARGB8_VALUE:
        return String.format("#%08X", primitiveMsg.getColorArgb8Value());

      case COLOR_RGB8_VALUE:
        return String.format("#%06X", primitiveMsg.getColorRgb8Value() & 0xFFFFFF);

      case COLOR_ARGB4_VALUE:
        int argb = primitiveMsg.getColorArgb4Value();
        return String.format("#%X%X%X%X", (argb >>> 24) & 0xF, (argb >>> 16) & 0xF, (argb >>> 8) & 0xF, argb & 0xF);

      case COLOR_RGB4_VALUE:
        int rgb = primitiveMsg.getColorRgb4Value();
        return String.format("#%X%X%X", (rgb >>> 16) & 0xF, (rgb >>> 8) & 0xF, rgb & 0xF);

      case ONEOFVALUE_NOT_SET:
      default:
        LOG.warn("Unexpected Primitive message: " + primitiveMsg);
        break;
    }
    return null;
  }

  /**
   * Decodes a dimension value in the Android binary XML encoding and returns a string suitable for regular XML.
   *
   * @param bits the encoded value
   * @param scaleFactor the scale factor to apply to the result
   * @param unitSuffixes the unit suffixes, either {@link #DIMEN_SUFFIXES} or {@link #FRACTION_SUFFIXES}
   * @return the decoded value as a string, e.g. "-6.5dp", or "60%"
   * @see <a href="https://android.googlesource.com/platform/frameworks/base/+/master/libs/androidfw/include/androidfw/ResourceTypes.h">
   *     ResourceTypes.h</a>
   */
  private static String decodeComplexDimensionValue(int bits, double scaleFactor, @NotNull String[] unitSuffixes) {
    int unitCode = bits & COMPLEX_UNIT_MASK;
    String unit = unitCode < unitSuffixes.length ? unitSuffixes[unitCode] : " unknown unit: " + unitCode;
    int radix = (bits >> COMPLEX_RADIX_SHIFT) & COMPLEX_RADIX_MASK;
    int mantissa = bits >> COMPLEX_MANTISSA_SHIFT;
    double value = mantissa * RADIX_FACTORS[radix] * scaleFactor;
    return XmlUtils.trimInsignificantZeros(String.format(Locale.US, "%.5g", value)) + unit;
  }

  @NotNull
  private static Set<AttributeFormat> decodeFormatFlags(int flags) {
    EnumSet<AttributeFormat> result = EnumSet.noneOf(AttributeFormat.class);
    if (BitUtil.isSet(flags, Resources.Attribute.FormatFlags.REFERENCE_VALUE)) {
      result.add(AttributeFormat.REFERENCE);
    }
    if (BitUtil.isSet(flags, Resources.Attribute.FormatFlags.STRING_VALUE)) {
      result.add(AttributeFormat.STRING);
    }
    if (BitUtil.isSet(flags, Resources.Attribute.FormatFlags.INTEGER_VALUE)) {
      result.add(AttributeFormat.INTEGER);
    }
    if (BitUtil.isSet(flags, Resources.Attribute.FormatFlags.BOOLEAN_VALUE)) {
      result.add(AttributeFormat.BOOLEAN);
    }
    if (BitUtil.isSet(flags, Resources.Attribute.FormatFlags.COLOR_VALUE)) {
      result.add(AttributeFormat.COLOR);
    }
    if (BitUtil.isSet(flags, Resources.Attribute.FormatFlags.FLOAT_VALUE)) {
      result.add(AttributeFormat.FLOAT);
    }
    if (BitUtil.isSet(flags, Resources.Attribute.FormatFlags.DIMENSION_VALUE)) {
      result.add(AttributeFormat.DIMENSION);
    }
    if (BitUtil.isSet(flags, Resources.Attribute.FormatFlags.FRACTION_VALUE)) {
      result.add(AttributeFormat.FRACTION);
    }
    if (BitUtil.isSet(flags, Resources.Attribute.FormatFlags.ENUM_VALUE)) {
      result.add(AttributeFormat.ENUM);
    }
    if (BitUtil.isSet(flags, Resources.Attribute.FormatFlags.FLAGS_VALUE)) {
      result.add(AttributeFormat.FLAGS);
    }
    return Collections.unmodifiableSet(result);
  }

  @NotNull
  private AarConfiguration getConfiguration(@NotNull Configuration configMsg, @NotNull Map<Configuration, AarConfiguration> cache) {
    AarConfiguration aarConfiguration = cache.get(configMsg);
    if (aarConfiguration != null) {
      return aarConfiguration;
    }

    FolderConfiguration configuration = ProtoConfigurationDecoder.getConfiguration(configMsg);

    aarConfiguration = new AarConfiguration(this, configuration);
    cache.put(configMsg, aarConfiguration);
    return aarConfiguration;
  }

  // For debugging only.
  @Override
  public String toString() {
    return getClass().getSimpleName() + '@' + Integer.toHexString(System.identityHashCode(this)) + " for " + myResApkFileOrFolder;
  }

  /**
   * Parser of resource URLs. Unlike {@link com.android.resources.ResourceUrl}, this class creates virtually no GC overhead.
   */
  private static class ResourceUrlParser {
    @NotNull String resourceUrl = "";
    int prefixEnd;
    int colonPos;
    int slashPos;

    /**
     * Parses resource URL and sets the fields of this object to point to different parts of the URL.
     *
     * @param resourceUrl the resource URL to parse
     */
    void parseResourceUrl(@NotNull String resourceUrl) {
      this.resourceUrl = resourceUrl;
      if (resourceUrl.startsWith(SdkConstants.PREFIX_RESOURCE_REF)) {
        if (resourceUrl.startsWith("@+")) {
          prefixEnd = 2;
        } else {
          prefixEnd = 1;
        }
      } else if (resourceUrl.startsWith(SdkConstants.PREFIX_THEME_REF)) {
        prefixEnd = 1;
      } else {
        prefixEnd = 0;
      }
      if (resourceUrl.startsWith("*", prefixEnd)) {
        prefixEnd++;
      }
      slashPos = resourceUrl.lastIndexOf('/');
      if (slashPos >= 0) {
        colonPos = resourceUrl.lastIndexOf(':', slashPos);
      } else {
        colonPos = resourceUrl.lastIndexOf(':');
      }
    }

    @Nullable
    String getPackageName() {
      return colonPos > prefixEnd ? resourceUrl.substring(prefixEnd, colonPos) : null;
    }

    int getTypeStart() {
      return colonPos >= 0 ? colonPos + 1 : prefixEnd;
    }

    boolean isType(@NotNull String type) {
      if (slashPos < 0) {
        return false;
      }
      int typeStart = getTypeStart();
      return resourceUrl.startsWith(type) && slashPos == typeStart + type.length();
    }

    @NotNull
    String getName() {
      int nameStart = slashPos >= 0 ? slashPos + 1 : colonPos >= 0 ? colonPos + 1 : prefixEnd;
      return resourceUrl.substring(nameStart);
    }

    @NotNull
    String withoutType() {
      if (slashPos < 0) {
        return resourceUrl;
      }
      int typeStart = getTypeStart();
      if (typeStart == 0) {
        return resourceUrl.substring(slashPos + 1);
      }
      return resourceUrl.substring(0, typeStart) + resourceUrl.substring(slashPos + 1);
    }
  }

  private static class DataLoader {
    private final File resApkFileOrFolder;
    Resources.ResourceTable resourceTableMsg;
    String packageName;
    boolean loadedFromResApk;

    DataLoader(@NotNull File resApkFileOrFolder) {
      this.resApkFileOrFolder = resApkFileOrFolder;
    }

    void load() throws IOException {
      try {
        resourceTableMsg = readResourceTableFromResourcesPbFile();
        packageName = AndroidManifestUtils
          .getPackageNameFromManifestFile(new PathString(new File(resApkFileOrFolder, SdkConstants.FN_ANDROID_MANIFEST_XML)));
      } catch (FileNotFoundException e) {
        try (ZipFile zipFile = new ZipFile(resApkFileOrFolder)) {
          resourceTableMsg = readResourceTableFromResApk(zipFile);
          packageName = AndroidManifestUtils.getPackageNameFromResApk(zipFile);
        }
        loadedFromResApk = true;
      }
    }

    /**
     * Loads resource table from resources.pb file under the AAR directory.
     *
     * @return the resource table proto message, or null if the resources.pb file does not exist
     */
    @Nullable
    private Resources.ResourceTable readResourceTableFromResourcesPbFile() throws IOException {
      File file = new File(resApkFileOrFolder, RESOURCE_TABLE_ENTRY);
      try (InputStream stream = new BufferedInputStream(new FileInputStream(file))) {
        return Resources.ResourceTable.parseFrom(stream);
      }
    }

    /**
     * Loads resource table from res.apk file.
     *
     * @return the resource table proto message
     */
    @NotNull
    private static Resources.ResourceTable readResourceTableFromResApk(@NotNull ZipFile resApk) throws IOException {
      ZipEntry zipEntry = resApk.getEntry(RESOURCE_TABLE_ENTRY);
      if (zipEntry == null) {
        throw new IOException("\"" + RESOURCE_TABLE_ENTRY + "\" not found in " + resApk.getName());
      }

      try (InputStream stream = new BufferedInputStream(resApk.getInputStream(zipEntry))) {
        return Resources.ResourceTable.parseFrom(stream);
      }
    }
  }
}
