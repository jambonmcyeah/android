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

import com.android.tools.adtui.common.AdtSecondaryPanel
import com.android.tools.adtui.model.stdui.ValueChangedListener
import com.android.tools.idea.common.property2.api.InspectorPanel
import com.android.tools.idea.common.property2.impl.model.InspectorPanelModel
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.wm.IdeGlassPaneUtil
import com.intellij.ui.AbstractExpandableItemsHandler
import com.intellij.util.ui.JBInsets
import com.intellij.util.ui.JBUI
import java.awt.Component
import java.awt.Insets
import java.awt.Point
import java.awt.Rectangle
import java.awt.event.MouseEvent
import java.awt.event.MouseEvent.MOUSE_EXITED
import java.awt.event.MouseMotionAdapter
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel
import javax.swing.SwingUtilities

private const val RIGHT_OVERLAY_MARGIN = 6

typealias ComponentBounds = com.intellij.openapi.util.Pair<Component, Rectangle>

/**
 * Implementation of [InspectorPanel].
 */
class InspectorPanelImpl(val model: InspectorPanelModel, parentDisposable: Disposable) :
    AdtSecondaryPanel(InspectorLayoutManager()), Disposable, ValueChangedListener {
  private val expandableLabelHandler = ExpandableLabelHandler(this)

  init {
    Disposer.register(parentDisposable, this)
    border = JBUI.Borders.empty()
    model.addValueChangedListener(this)
  }

  override fun addNotify() {
    super.addNotify()
    expandableLabelHandler.install(this)
  }

  override fun removeNotify() {
    super.removeNotify()
    expandableLabelHandler.remove()
  }

  fun addLineElement(component: JComponent) {
    add(component, Placement.LINE)
  }

  fun addLineElement(label: CollapsibleLabel, component: JComponent) {
    add(label, Placement.LEFT)
    add(component, Placement.RIGHT)
  }

  override fun dispose() {
    model.removeValueChangedListener(this)
  }

  override fun valueChanged() {
    revalidate()
    repaint()
  }

  /**
   * Handles expansion of property labels.
   *
   * The tricky part of this code is to be able to show ellipsis in labels
   * that are too wide, but remove the ellipsis when the text is expanded.
   */
  private class ExpandableLabelHandler(component: InspectorPanelImpl) :
      AbstractExpandableItemsHandler<CollapsibleLabel, JPanel>(component) {
    private val mousePreprocessor: MousePreprocessor
    private val renderer: JLabel
    private var expandedLabel: CollapsibleLabel? = null

    init {
      mousePreprocessor = MousePreprocessor()
      renderer = JLabel()
    }

    fun install(parent: Disposable) {
      val glassPane = IdeGlassPaneUtil.find(myComponent)
      glassPane.addMouseMotionPreprocessor(mousePreprocessor, parent)
    }

    fun remove() {
      val glassPane = IdeGlassPaneUtil.find(myComponent)
      glassPane.removeMouseMotionPreprocessor(mousePreprocessor)
    }

    override fun getCellRendererAndBounds(key: CollapsibleLabel): ComponentBounds? {
      if (expandedLabel != key) {
        // Hide any previously expanded label
        hideExpansion()
      }
      if (key.preferredSize.width <= key.width) {
        // If the text fits the current size there is nothing to expand
        return null
      }
      if (!ApplicationManager.getApplication().isActive) {
        // Only expand if this is the active application
        return null
      }

      // This renderer is used to display the text that expands to the right
      // of the original label component. This is done in a popup in
      //   [AbstractExpandableItemsHandler].
      renderer.text = key.text
      renderer.icon = key.icon
      renderer.font = key.font
      renderer.foreground = key.foreground
      renderer.background = key.background

      // Record the label being expanded and hide the ellipsis at the end of the text.
      expandedLabel = key
      expandedLabel?.model?.showEllipses = false
      return ComponentBounds.create(renderer, overlayBounds(key))
    }

    override fun onFocusLost() {
      super.onFocusLost()
      hideExpansion()
    }

    override fun isPaintBorder(): Boolean {
      return false
    }

    override fun getVisibleRect(key: CollapsibleLabel): Rectangle {
      return SwingUtilities.convertRectangle(key, key.visibleRect, myComponent)
    }

    override fun getCellKeyForPoint(point: Point): CollapsibleLabel? {
      val component = myComponent.getComponentAt(point.x, point.y)
      return component as? CollapsibleLabel
    }

    private fun hideExpansion(): Boolean {
      val wasExpanded = expandedLabel != null
      expandedLabel?.model?.showEllipses = true
      expandedLabel = null
      return wasExpanded
    }

    private fun overlayBounds(key: CollapsibleLabel): Rectangle {
      val bounds = Rectangle(key.location, key.preferredSize)
      val insets = key.border?.getBorderInsets(key) ?: Insets(0, 0, 0, 0)
      JBInsets.removeFrom(bounds, insets)
      bounds.height = (myComponent.layout as? InspectorLayoutManager)?.getRowHeight(key) ?: key.preferredSize.height
      bounds.width += JBUI.scale(RIGHT_OVERLAY_MARGIN) // Give a little extra room in the overlay
      return bounds
    }

    /**
     * Adapter for preprocessing all mouse move events on the IdeGlassPane.
     *
     * If the mouse is on top of the inspector, start investigating any
     * [CollapsibleLabel] under the mouse. Otherwise reset any existing
     * label expansion.
     */
    private inner class MousePreprocessor : MouseMotionAdapter() {
      override fun mouseMoved(event: MouseEvent) {
        val point = SwingUtilities.convertPoint(event.component, event.point, myComponent)
        if (myComponent.contains(point)) {
          val component = myComponent.getComponentAt(point.x, point.y)
          if (component is CollapsibleLabel) {
            handleSelectionChange(component, true)
          }
        }
        else {
          if (hideExpansion()) {
            // Hack: If the mouse is moved quickly out of the inspector component, the popup from
            // AbstractExpandableItemsHandler would sometimes stay open because the component
            // did not receive a proper MOUSE_EXITED event. So generate one here to force the popup
            // to close.
            val fakeExited = MouseEvent(event.component, MOUSE_EXITED, event.`when`, 0, -1, -1, 0,false)
            myComponent.dispatchEvent(fakeExited)
          }
        }
      }
    }
  }
}
