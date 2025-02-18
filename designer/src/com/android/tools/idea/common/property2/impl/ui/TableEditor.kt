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
package com.android.tools.idea.common.property2.impl.ui

import com.android.tools.adtui.model.stdui.ValueChangedListener
import com.android.tools.adtui.ptable2.PTable
import com.android.tools.adtui.ptable2.PTableCellEditorProvider
import com.android.tools.adtui.ptable2.PTableCellRendererProvider
import com.android.tools.adtui.ptable2.PTableColumn
import com.android.tools.idea.common.property2.api.PropertyItem
import com.android.tools.idea.common.property2.impl.model.TableLineModelImpl
import java.awt.event.MouseEvent
import javax.swing.JTable

/**
 * A standard table control for editing multiple properties in a tabular form.
 */
class TableEditor(val lineModel: TableLineModelImpl,
                  rendererProvider: PTableCellRendererProvider,
                  editorProvider: PTableCellEditorProvider) {

  private val table = PTable.create(lineModel.tableModel, lineModel, rendererProvider, editorProvider) { getToolTipText(it) }
  val component = table.component as JTable

  init {
    lineModel.addValueChangedListener(ValueChangedListener { handleValueChanged() })
    component.selectionModel.addListSelectionListener {
      val model = lineModel.tableModel
      val index = component.selectedRow
      val item = if (index >= 0 && index < model.items.size) model.items[index] else null
      lineModel.selectedItem = item
    }
  }

  private fun handleValueChanged() {
    component.isVisible = lineModel.visible
    table.filter = lineModel.filter
    if (lineModel.updateEditing) {
      table.startEditing(lineModel.rowToEdit)
    }
  }

  private fun getToolTipText(event: MouseEvent): String? {
    val tableRow = component.rowAtPoint(event.point)
    val tableColumn = component.columnAtPoint(event.point)
    val index = component.convertRowIndexToModel(tableRow)
    val column = PTableColumn.fromColumn(tableColumn)
    val property = lineModel.tableModel.items[index] as PropertyItem
    return PropertyTooltip.setToolTip(component, event, property, column == PTableColumn.VALUE, null)
  }
}
