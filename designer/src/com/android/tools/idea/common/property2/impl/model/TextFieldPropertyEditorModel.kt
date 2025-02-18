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
package com.android.tools.idea.common.property2.impl.model

import com.android.tools.adtui.model.stdui.CommonTextFieldModel
import com.android.tools.adtui.model.stdui.EditingSupport
import com.android.tools.idea.common.property2.api.PropertyItem
import kotlin.properties.Delegates

/**
 * Model for properties that use a Text Editor.
 */
open class TextFieldPropertyEditorModel(property: PropertyItem, override val editable: Boolean) :
  BasePropertyEditorModel(property), CommonTextFieldModel {

  /**
   * A property change is pending.
   *
   * Indicates if a change to the property value was initiated, but the value wasn't
   * immediately registered by the property. Use this value to omit change requests
   * generated from [focusLost].
   */
  protected var pendingValueChange = false

  override var text: String by Delegates.observable(property.value.orEmpty()) { _, _, _ -> pendingValueChange = false }

  override val editingSupport: EditingSupport
    get() = property.editingSupport

  override fun enterKeyPressed() {
    if (commitChange()) {
      // This call may navigate away from the editor control.
      super.enterKeyPressed()
    }
  }

  fun escape() {
    cancelEditing()
  }

  override fun focusLost() {
    super.focusLost()
    commitChange()
  }

  /**
   * Commit the current changed text.
   *
   * Return true if the change was successfully updated.
   */
  private fun commitChange(): Boolean {
    if (pendingValueChange || text == value) {
      return !pendingValueChange
    }
    pendingValueChange = value != text
    value = text
    pendingValueChange = value != text
    return !pendingValueChange
  }
}
