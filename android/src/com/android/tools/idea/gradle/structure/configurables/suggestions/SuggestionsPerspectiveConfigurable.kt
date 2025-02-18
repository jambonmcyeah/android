/*
 * Copyright (C) 2016 The Android Open Source Project
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
package com.android.tools.idea.gradle.structure.configurables.suggestions

import com.android.tools.idea.gradle.structure.configurables.AbstractCounterDisplayConfigurable
import com.android.tools.idea.gradle.structure.configurables.JavaModuleUnsupportedConfigurable
import com.android.tools.idea.gradle.structure.configurables.PsContext
import com.android.tools.idea.gradle.structure.configurables.android.dependencies.PsAllModulesFakeModule
import com.android.tools.idea.gradle.structure.configurables.android.modules.AbstractModuleConfigurable
import com.android.tools.idea.gradle.structure.model.PsModule
import com.android.tools.idea.gradle.structure.model.android.PsAndroidModule
import com.android.tools.idea.structure.dialog.TrackedConfigurable
import com.google.wireless.android.sdk.stats.PSDEvent
import com.intellij.util.ui.UIUtil.invokeLaterIfNeeded
import javax.swing.JComponent

class SuggestionsPerspectiveConfigurable(context: PsContext)
  : AbstractCounterDisplayConfigurable(context, extraModules = listOf(PsAllModulesFakeModule(context.project))), TrackedConfigurable {
  private var messageCount: Int = 0

  override val leftConfigurable = PSDEvent.PSDLeftConfigurable.PROJECT_STRUCTURE_DIALOG_LEFT_CONFIGURABLE_SUGGESTIONS

  override fun getId(): String = "android.psd.suggestions"

  override fun createConfigurableFor(module: PsModule): AbstractModuleConfigurable<PsModule, *> =
      when (module) {
        is PsAndroidModule -> createConfigurable(module)
        is PsAllModulesFakeModule -> createConfigurable(module)
        else -> JavaModuleUnsupportedConfigurable(context, module)
      }

  override val navigationPathName: String = "suggestions.place"

  override fun getDisplayName(): String = "Suggestions"


  override fun getCount(): Int = messageCount

  override fun createComponent(): JComponent {
    val component = super.createComponent().apply { name = "SuggestionsView" }
    context.analyzerDaemon.add({
      fireCountChangeListener()
      invokeLaterIfNeeded { messageCount = getIssues(context, null).size }
    }, this)
    return component
  }

  private fun createConfigurable(module: PsModule) =
      AndroidModuleSuggestionsConfigurable(context, module, extraModules).apply { setHistory(myHistory) }
}
