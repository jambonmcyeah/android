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
package com.android.tools.idea.resourceExplorer.sketchImporter.converter;

import com.android.tools.idea.resourceExplorer.sketchImporter.parser.pages.SketchStyle;
import com.google.common.collect.ImmutableList;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.HashMap;

/**
 * Class that holds a mapping between a {@link SketchStyle} and its sharedObjectId.
 */
public class StylesLibrary {
  private HashMap<String, SketchStyle> myStyleHashMap = new HashMap<>();

  public void addStyles(@NotNull ImmutableList<SketchStyle> styles) {
    for (SketchStyle style : styles) {
      myStyleHashMap.put(style.getSharedObjectID(), style);
    }
  }

  @Nullable
  public SketchStyle getStyle(@NotNull String objectId) {
    return myStyleHashMap.get(objectId);
  }

  public boolean isEmpty() {
    return myStyleHashMap.isEmpty();
  }
}
