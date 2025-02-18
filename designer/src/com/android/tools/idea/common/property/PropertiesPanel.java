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
package com.android.tools.idea.common.property;

import com.android.tools.idea.common.model.NlComponent;
import com.android.tools.idea.uibuilder.property.NlPropertyItem;
import com.google.common.collect.Table;
import com.intellij.ide.CopyProvider;
import com.intellij.ide.CutProvider;
import com.intellij.ide.DeleteProvider;
import com.intellij.ide.PasteProvider;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.actionSystem.DataProvider;
import org.jetbrains.annotations.NotNull;

import javax.swing.*;
import java.awt.*;
import java.util.List;

public abstract class PropertiesPanel<PropMgr extends PropertiesManager<PropMgr>>
  extends JPanel
  implements Disposable, DataProvider, DeleteProvider, CutProvider, CopyProvider, PasteProvider {
  public PropertiesPanel(LayoutManager layout) {
    super(layout);
  }

  public abstract void activatePreferredEditor(@NotNull String propertyName, boolean afterload);

  public abstract void modelRendered();

  public abstract void setItems(@NotNull List<NlComponent> components,
                                @NotNull Table<String, String, NlPropertyItem> properties);
}
