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
package com.android.tools.idea.uibuilder.property2.support

import com.android.SdkConstants.*
import com.android.tools.idea.uibuilder.property2.NelePropertyType
import com.google.common.truth.Truth.assertThat
import org.jetbrains.android.dom.attrs.AttributeDefinition
import com.android.ide.common.rendering.api.AttributeFormat
import com.android.ide.common.rendering.api.ResourceNamespace
import org.junit.Test

class TypeResolverTest {

  @Test
  fun testFromAttributeDefinition() {
    assertThat(resolve(ATTR_TEXT_ALL_CAPS, AttributeFormat.BOOLEAN)).isEqualTo(NelePropertyType.THREE_STATE_BOOLEAN)
    assertThat(resolve(ATTR_TEXT_COLOR, AttributeFormat.COLOR)).isEqualTo(NelePropertyType.COLOR_OR_DRAWABLE)
    assertThat(resolve(ATTR_ELEVATION, AttributeFormat.DIMENSION)).isEqualTo(NelePropertyType.DIMENSION)
    assertThat(resolve(ATTR_MAXIMUM, AttributeFormat.FLOAT)).isEqualTo(NelePropertyType.FLOAT)
    assertThat(resolve(ATTR_TEXT_ALL_CAPS, AttributeFormat.FRACTION)).isEqualTo(NelePropertyType.FRACTION)
    assertThat(resolve(ATTR_ELEVATION, AttributeFormat.INTEGER)).isEqualTo(NelePropertyType.INTEGER)
    assertThat(resolve(ATTR_TEXT, AttributeFormat.STRING)).isEqualTo(NelePropertyType.STRING)
  }

  @Test
  fun testFromNameLookup() {
    assertThat(TypeResolver.resolveType(ATTR_STYLE, null)).isEqualTo(NelePropertyType.STYLE)
    assertThat(TypeResolver.resolveType(ATTR_CLASS, null)).isEqualTo(NelePropertyType.FRAGMENT)
    assertThat(TypeResolver.resolveType(ATTR_LAYOUT, null)).isEqualTo(NelePropertyType.LAYOUT)
    assertThat(TypeResolver.resolveType(ATTR_SHOW_IN, null)).isEqualTo(NelePropertyType.LAYOUT)
  }

  @Test
  fun testFromNameFallback() {
    assertThat(TypeResolver.resolveType(ATTR_BACKGROUND, null)).isEqualTo(NelePropertyType.COLOR_OR_DRAWABLE)
    assertThat(TypeResolver.resolveType(ATTR_BACKGROUND_TINT, null)).isEqualTo(NelePropertyType.COLOR_OR_DRAWABLE)
    assertThat(TypeResolver.resolveType(ATTR_TEXT_APPEARANCE, null)).isEqualTo(NelePropertyType.TEXT_APPEARANCE)
    assertThat(TypeResolver.resolveType(ATTR_SWITCH_TEXT_APPEARANCE, null)).isEqualTo(NelePropertyType.TEXT_APPEARANCE)
    assertThat(TypeResolver.resolveType(ATTR_SCROLLBAR_STYLE, null)).isEqualTo(NelePropertyType.STYLE)
    assertThat(TypeResolver.resolveType("XYZ", null)).isEqualTo(NelePropertyType.STRING)
  }

  private fun resolve(name: String, format: AttributeFormat): NelePropertyType {
    val definition = AttributeDefinition(ResourceNamespace.RES_AUTO, name, null, setOf(format))
    return TypeResolver.resolveType(name, definition)
  }
}
