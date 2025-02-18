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
package com.android.tools.idea.gradle.dsl.model.dependencies;

import com.android.tools.idea.gradle.dsl.api.dependencies.ModuleDependencyModel;
import com.android.tools.idea.gradle.dsl.api.ext.ResolvedPropertyModel;
import com.android.tools.idea.gradle.dsl.model.ext.GradlePropertyModelBuilder;
import com.android.tools.idea.gradle.dsl.model.ext.transforms.MapMethodTransform;
import com.android.tools.idea.gradle.dsl.model.ext.transforms.SingleArgToMapTransform;
import com.android.tools.idea.gradle.dsl.model.ext.transforms.SingleArgumentMethodTransform;
import com.android.tools.idea.gradle.dsl.parser.elements.GradleDslExpressionMap;
import com.android.tools.idea.gradle.dsl.parser.elements.GradleDslMethodCall;
import com.android.tools.idea.gradle.dsl.parser.elements.GradleNameElement;
import com.android.tools.idea.gradle.dsl.parser.elements.GradlePropertiesDslElement;
import com.google.common.base.Joiner;
import com.google.common.base.Splitter;
import com.google.common.collect.Lists;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

import static com.android.SdkConstants.GRADLE_PATH_SEPARATOR;
import static com.android.tools.idea.gradle.util.GradleUtil.getPathSegments;

public class ModuleDependencyModelImpl extends DependencyModelImpl implements
                                                                   ModuleDependencyModel {
  @NonNls public static final String PROJECT = "project";
  @NonNls private static final String PATH = "path";
  @NonNls private static final String CONFIGURATION = "configuration";

  @NotNull private String myConfigurationName;
  @NotNull private GradleDslMethodCall myDslElement;

  @Nullable
  static ModuleDependencyModel create(@NotNull String configurationName, @NotNull GradleDslMethodCall methodCall) {
    if (PROJECT.equals(methodCall.getMethodName())) {
      return new ModuleDependencyModelImpl(configurationName, methodCall);
    }
    return null;
  }

  @NotNull
  static ModuleDependencyModel create(@NotNull GradlePropertiesDslElement parent,
                     @NotNull String configurationName,
                     @NotNull String path,
                     @Nullable String config) {
    GradleNameElement name = GradleNameElement.create(configurationName);
    GradleDslMethodCall methodCall = new GradleDslMethodCall(parent, name, PROJECT);
    GradleDslExpressionMap mapArguments = new GradleDslExpressionMap(methodCall, name);
    mapArguments.setNewLiteral(PATH, path);
    if (config != null) {
      mapArguments.setNewLiteral(CONFIGURATION, config);
    }
    methodCall.addNewArgument(mapArguments);
    parent.setNewElement(methodCall);
    return  new ModuleDependencyModelImpl(configurationName, methodCall);
  }

  private ModuleDependencyModelImpl(@NotNull String configurationName,
                                    @NotNull GradleDslMethodCall dslElement) {
    myConfigurationName = configurationName;
    myDslElement = dslElement;
  }

  @Override
  @NotNull
  protected GradleDslMethodCall getDslElement() {
    return myDslElement;
  }

  @Override
  @NotNull
  public String configurationName() {
    return myConfigurationName;
  }

  @Override
  @NotNull
  public String name() {
    List<String> pathSegments = getPathSegments(path().forceString());
    int segmentCount = pathSegments.size();
    return segmentCount > 0 ? pathSegments.get(segmentCount - 1) : "";
  }

  @Override
  public void setName(@NotNull String name) {
    String newPath;
    ResolvedPropertyModel path = path();

    // Keep empty spaces, needed when putting the path back together
    List<String> segments = Splitter.on(GRADLE_PATH_SEPARATOR).splitToList(path.forceString());
    List<String> modifiableSegments = Lists.newArrayList(segments);
    int segmentCount = modifiableSegments.size();
    if (segmentCount == 0) {
      newPath = GRADLE_PATH_SEPARATOR + name.trim();
    }
    else {
      modifiableSegments.set(segmentCount - 1, name);
      newPath = Joiner.on(GRADLE_PATH_SEPARATOR).join(modifiableSegments);
    }
    path.setValue(newPath);
  }

  @Override
  @NotNull
  public ResolvedPropertyModel path() {
    return GradlePropertyModelBuilder.create(myDslElement).asMethod(true).addTransform(new MapMethodTransform(PROJECT, PATH))
                                     .addTransform(new SingleArgumentMethodTransform(PROJECT)).buildResolved();
  }

  @Override
  @NotNull
  public ResolvedPropertyModel configuration() {
    return GradlePropertyModelBuilder.create(myDslElement).asMethod(true).addTransform(new SingleArgToMapTransform(PATH, CONFIGURATION))
                                     .addTransform(new MapMethodTransform(PROJECT, CONFIGURATION)).buildResolved();
  }
}
