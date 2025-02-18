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
package com.android.tools.idea.gradle.structure.configurables.ui.modules

import com.android.tools.idea.gradle.structure.configurables.android.modules.defaultConfigPropertiesModel
import com.android.tools.idea.gradle.structure.configurables.ui.ModelPanel
import com.android.tools.idea.gradle.structure.configurables.ui.properties.ConfigPanel
import com.android.tools.idea.gradle.structure.model.android.PsAndroidModule
import com.android.tools.idea.gradle.structure.model.android.PsAndroidModuleDefaultConfig
import com.intellij.openapi.util.ActionCallback
import com.intellij.ui.navigation.History
import com.intellij.ui.navigation.Place

class ModuleDefaultConfigConfigPanel(defaultConfig: PsAndroidModuleDefaultConfig) :
  ConfigPanel<PsAndroidModuleDefaultConfig>(
    defaultConfig.module,
    defaultConfig,
    defaultConfigPropertiesModel(defaultConfig.module.isLibrary)
  ),
  ModelPanel<PsAndroidModule> {

  override val title = "Default Config"
  override fun setHistory(history: History?) = Unit
  override fun navigateTo(place: Place?, requestFocus: Boolean): ActionCallback = ActionCallback.DONE
  override fun queryPlace(place: Place) = Unit
}
