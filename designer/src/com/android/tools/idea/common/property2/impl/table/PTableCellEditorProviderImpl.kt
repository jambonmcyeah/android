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
package com.android.tools.idea.common.property2.impl.table

import com.android.annotations.VisibleForTesting
import com.android.tools.adtui.ptable2.*
import com.android.tools.idea.common.property2.api.*
import com.android.tools.idea.common.property2.impl.model.TableLineModelImpl
import com.android.tools.idea.common.property2.impl.ui.CellPanel
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Color
import javax.swing.JComponent
import javax.swing.border.Border

/**
 * Implementation of a [PTableCellEditorProvider]
 *
 * Create a provider for a [PTableCellEditor] based on the column being edited.
 * If the user is editing a property name:
 *    use the [nameControlTypeProvider] and [nameEditorProvider] for creating
 *    an editor for a [nameType] property.
 * If the user is editing a property value:
 *    use the [valueControlTypeProvider] and [valueEditorProvider] for creating
 *    an editor for a [valueType] property.
 */
class PTableCellEditorProviderImpl<N : NewPropertyItem, P : PropertyItem>(
  private val nameType: Class<N>,
  private val nameControlTypeProvider: ControlTypeProvider<N>,
  private val nameEditorProvider: EditorProvider<N>,
  private val valueType: Class<P>,
  private val valueControlTypeProvider: ControlTypeProvider<P>,
  private val valueEditorProvider: EditorProvider<P>) : PTableCellEditorProvider {

  private val defaultEditor = DefaultPTableCellEditor()
  private val editor = PTableCellEditorImpl()

  override fun invoke(table: PTable, property: PTableItem, column: PTableColumn): PTableCellEditor {
    when (column) {
      PTableColumn.NAME -> {
        if (!nameType.isInstance(property)) {
          return defaultEditor
        }
        val newProperty = nameType.cast(property)
        val controlType = nameControlTypeProvider(newProperty)
        val (newModel, newEditor) = nameEditorProvider.createEditor(newProperty, asTableCellEditor = true)
        val border = JBUI.Borders.empty(0, LEFT_STANDARD_INDENT - newEditor.insets.left, 0, 0)
        editor.nowEditing(table, property, column, controlType, newModel, EditorPanel(newEditor, border, table.backgroundColor))
      }

      PTableColumn.VALUE -> {
        if (!valueType.isInstance(property)) {
          return defaultEditor
        }
        val valueProperty = valueType.cast(property)
        val controlType = valueControlTypeProvider(valueProperty)
        val (newModel, newEditor) = valueEditorProvider.createEditor(valueProperty, asTableCellEditor = true)
        val border = JBUI.Borders.customLine(table.gridLineColor, 0, 1, 0, 0)
        editor.nowEditing(table, property, column, controlType, newModel, EditorPanel(newEditor, border, table.backgroundColor))
      }
    }
    return editor
  }
}

class PTableCellEditorImpl : PTableCellEditor {

  private var table: PTable? = null
  private var model: PropertyEditorModel? = null
  private var controlType: ControlType? = null
  private var item: PTableItem? = null
  private var column: PTableColumn? = null

  override var editorComponent: JComponent? = null
    private set

  override val value: String?
    get() = model?.value

  override val isBooleanEditor: Boolean
    get() = controlType == ControlType.THREE_STATE_BOOLEAN || controlType == ControlType.BOOLEAN

  override fun toggleValue() {
    model?.toggleValue()
  }

  override fun requestFocus() {
    model?.requestFocus()
  }

  override fun cancelEditing(): Boolean {
    return model?.cancelEditing() ?: true
  }

  override fun close(oldTable: PTable) {
    if (table == oldTable) {
      table = null
      item = null
      column = null
      model = null
      controlType = null
      editorComponent = null
    }
  }

  fun nowEditing(newTable: PTable, newItem: PTableItem, newColumn: PTableColumn,
                 newControlType: ControlType, newModel: PropertyEditorModel, newEditor: JComponent) {
    newModel.onEnter = { startNextEditor() }

    table = newTable
    item = newItem
    column = newColumn
    model = newModel
    controlType = newControlType
    editorComponent = newEditor
  }

  private fun startNextEditor() {
    val currentTable = table ?: return
    val currentItem = item ?: return
    val currentColumn = column ?: return
    val model = currentTable.context as TableLineModelImpl
    if (model.tableModel.acceptMoveToNextEditor(currentItem, currentColumn)) {
      if (!currentTable.startNextEditor()) {
        model.gotoNextLine(model)
      }
    }
  }
}

@VisibleForTesting
class EditorPanel(val editor: JComponent, withBorder: Border, backgroundColor: Color?): CellPanel() {

  init {
    add(editor, BorderLayout.CENTER)
    border = withBorder
    background = backgroundColor
  }

  override fun requestFocus() {
    editor.requestFocus()
  }
}
