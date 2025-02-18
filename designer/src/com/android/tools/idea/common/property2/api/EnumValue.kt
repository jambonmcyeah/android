/*
 * Copyright (C) 2017 The Android Open Source Project
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
package com.android.tools.idea.common.property2.api

import com.android.tools.idea.common.property2.impl.support.*
import com.android.tools.idea.common.property2.impl.ui.EnumValueListCellRenderer
import com.intellij.openapi.actionSystem.AnAction
import javax.swing.ListCellRenderer

/**
 * Representation of values for the builtin [EnumSupport].
 *
 * This interface supports groups and separators in a popup.
 * Group headers and separators will be specified as a
 * property of an item. The idea is to ease the implementation
 * for skipping the headers and separators while navigating
 * the elements in the popup.
 *
 * Example:
 *
 *    Android
 *       option1
 *       option2
 *    AppCompat
 *       option1
 *       option2
 *    ----------
 *       option3
 */
interface EnumValue {
  /**
   * The actual value to read/write to a [PropertyItem].
   */
  val value: String

  /**
   * The value to display in a ComboBox popup control.
   *
   * This value may be identical to [value] or may be a
   * user-friendly representation of it.
   */
  val display: String
    get() = value

  /**
   * If true, display a separator above this value in the ComboBox popup.
   */
  val separator: Boolean
    get() = false

  /**
   * If true, indent this value in the ComboBox popup.
   */
  val indented: Boolean
    get() = false

  /**
   * If specified, display a header before this item in the ComboBox popup.
   */
  val header: String
    get() = ""

  /**
   * Specifies the operation done when this value is selected.
   *
   * The default operation simply updates the value on the property.
   * This method could be overridden to do something different like as
   * opening a dialog.
   */
  fun select(property: PropertyItem) {
    property.value = value
  }

  /**
   * Convenience method for creating a variant [EnumValue] with a header.
   */
  fun withHeader(header: String): EnumValue

  /**
   * Convenience method for creating a variant [EnumValue] with a separator.
   */
  fun withSeparator(): EnumValue

  /**
   * Convenience method for creating a variant [EnumValue] with indentation.
   */
  fun withIndentation(): EnumValue

  /** Default implementations of [EnumValue]s */
  companion object {
    fun item(value: String): EnumValue = ItemEnumValue(value)
    fun item(value: String, display: String): EnumValue = ItemWithDisplayEnumValue(value, display)
    fun indented(value: String): EnumValue = IndentedItemEnumValue(value)
    fun indented(value: String, display: String): EnumValue = IndentedItemWithDisplayEnumValue(value, display)
    fun action(action: AnAction): BaseActionEnumValue = ActionEnumValue(action)
    val DEFAULT_RENDERER: ListCellRenderer<EnumValue> = EnumValueListCellRenderer()
  }
}
