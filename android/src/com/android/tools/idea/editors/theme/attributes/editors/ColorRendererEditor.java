/*
 * Copyright (C) 2015 The Android Open Source Project
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
package com.android.tools.idea.editors.theme.attributes.editors;

import com.android.ide.common.rendering.api.AttributeFormat;
import com.android.ide.common.resources.ResourceResolver;
import com.android.resources.ResourceType;
import com.android.tools.idea.editors.theme.ColorUtils;
import com.android.tools.idea.editors.theme.ResolutionUtils;
import com.android.tools.idea.editors.theme.ThemeEditorContext;
import com.android.tools.idea.editors.theme.ThemeEditorUtils;
import com.android.tools.idea.editors.theme.datamodels.EditedStyleItem;
import com.android.tools.idea.editors.theme.preview.AndroidThemePreviewPanel;
import com.android.tools.idea.editors.theme.ui.ResourceComponent;
import com.android.tools.idea.res.ResourceHelper;
import com.android.tools.idea.ui.resourcechooser.ResourceSwatchComponent;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Iterables;
import com.intellij.openapi.util.text.StringUtil;
import org.jetbrains.android.dom.attrs.AttributeDefinition;
import org.jetbrains.annotations.NotNull;

import java.awt.*;
import java.util.EnumSet;
import java.util.List;

/**
 * Class that implements a {@link javax.swing.JTable} renderer and editor for color attributes.
 */
public class ColorRendererEditor extends GraphicalResourceRendererEditor {
  public ColorRendererEditor(@NotNull ThemeEditorContext context, @NotNull AndroidThemePreviewPanel previewPanel, boolean isEditor) {
    super(context, previewPanel, isEditor);
  }

  @Override
  protected void updateComponent(@NotNull ThemeEditorContext context, @NotNull ResourceComponent component, @NotNull EditedStyleItem item) {
    ResourceResolver resourceResolver = context.getResourceResolver();
    assert resourceResolver != null;

    final List<Color> colors = ResourceHelper.resolveMultipleColors(resourceResolver, item.getSelectedValue(), context.getProject());
    ResourceSwatchComponent.SwatchIcon icon;
    if (colors.isEmpty()) {
      icon = ResourceSwatchComponent.WARNING_ICON;
    }
    else {
      icon = new ResourceSwatchComponent.ColorIcon(Iterables.getLast(colors));
      icon.setIsStack(colors.size() > 1);
    }
    component.setSwatchIcon(icon);
    component.setNameText(item.getQualifiedName());
    component.setValueText(item.getValue());

    if (!colors.isEmpty()) {
      Color color = Iterables.getLast(colors);
      String attributeName = item.getAttrName();
      ImmutableMap<String, Color> contrastColorsWithDescription = ColorUtils.getContrastColorsWithDescription(context, attributeName);
      component.setWarning(
        ColorUtils.getContrastWarningMessage(contrastColorsWithDescription, color, ColorUtils.isBackgroundAttribute(attributeName)));
    }
    else {
      component.setWarning(null);
    }
  }

  @NotNull
  @Override
  protected EnumSet<ResourceType> getAllowedResourceTypes() {
    AttributeDefinition attrDefinition = ResolutionUtils.getAttributeDefinition(myContext.getConfiguration(), myItem.getSelectedValue());

    String attributeName = StringUtil.toLowerCase(myItem.getAttrName());
    if (attributeName.contains("color") || !ThemeEditorUtils.acceptsFormat(attrDefinition, AttributeFormat.REFERENCE)) {
      return COLORS_ONLY;
    }
    else if (attributeName.contains("drawable") || !ThemeEditorUtils.acceptsFormat(attrDefinition, AttributeFormat.COLOR)) {
      return DRAWABLES_ONLY;
    }
    else {
      return COLORS_AND_DRAWABLES;
    }
  }
}
