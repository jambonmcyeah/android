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
package com.android.tools.idea.uibuilder.palette2;

import com.android.annotations.VisibleForTesting;
import com.android.tools.adtui.common.AdtSecondaryPanel;
import com.android.tools.adtui.common.StudioColorsKt;
import com.android.tools.adtui.workbench.StartFilteringListener;
import com.android.tools.adtui.workbench.ToolContent;
import com.android.tools.idea.common.analytics.NlUsageTrackerManager;
import com.android.tools.idea.common.api.DragType;
import com.android.tools.idea.common.api.InsertType;
import com.android.tools.idea.common.model.*;
import com.android.tools.idea.common.surface.DesignSurface;
import com.android.tools.idea.common.surface.SceneView;
import com.android.tools.idea.configurations.Configuration;
import com.android.tools.idea.uibuilder.actions.ComponentHelpAction;
import com.android.tools.idea.uibuilder.palette.Palette;
import com.android.tools.idea.uibuilder.surface.NlDesignSurface;
import com.intellij.ide.BrowserUtil;
import com.intellij.ide.CopyProvider;
import com.intellij.ide.DataManager;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.actionSystem.*;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.ide.CopyPasteManager;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.project.DumbService;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.ui.ScrollPaneFactory;
import com.intellij.ui.scale.JBUIScale;
import com.intellij.util.ui.JBUI;
import org.jetbrains.android.facet.AndroidFacet;
import org.jetbrains.annotations.NonNls;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.jetbrains.annotations.TestOnly;

import javax.swing.*;
import java.awt.*;
import java.awt.datatransfer.Transferable;
import java.awt.datatransfer.UnsupportedFlavorException;
import java.awt.dnd.DnDConstants;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.lang.ref.WeakReference;
import java.util.List;
import java.util.function.Supplier;

import static javax.swing.ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER;
import static javax.swing.ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED;

/**
 * Top level Palette UI.
 */
public class PalettePanel extends AdtSecondaryPanel implements Disposable, DataProvider, ToolContent<DesignSurface> {
  private static final int DOWNLOAD_WIDTH = 16;
  private static final int VERTICAL_SCROLLING_UNIT_INCREMENT = 50;
  private static final int VERTICAL_SCROLLING_BLOCK_INCREMENT = 25;

  private final Project myProject;
  private final DependencyManager myDependencyManager;
  private final DataModel myDataModel;
  private final CopyProvider myCopyProvider;
  private final CategoryList myCategoryList;
  private final JScrollPane myCategoryScrollPane;
  private final ItemList myItemList;
  private final AddToDesignAction myAddToDesignAction;
  private final FavoriteAction myFavoriteAction;
  private final ComponentHelpAction myAndroidDocAction;
  private final MaterialDocAction myMaterialDocAction;
  private final ActionGroup myActionGroup;
  private final KeyListener myFilterKeyListener;

  @NotNull private WeakReference<DesignSurface> myDesignSurface = new WeakReference<>(null);
  private NlLayoutType myLayoutType;
  private Runnable myCloseAutoHideCallback;
  private StartFilteringListener myStartFilteringCallback;
  private Runnable myStopFilteringCallback;
  private Palette.Group myLastSelectedGroup;

  public PalettePanel(@NotNull Project project) {
    this(project, new DependencyManager(project));
  }

  @VisibleForTesting
  PalettePanel(@NotNull Project project, @NotNull DependencyManager dependencyManager) {
    super(new BorderLayout());
    myProject = project;
    myDependencyManager = dependencyManager;
    myDataModel = new DataModel(myDependencyManager);
    myDependencyManager.addDependencyChangeListener(() -> repaint());
    myCopyProvider = new CopyProviderImpl();
    Disposer.register(this, dependencyManager);

    myCategoryList = new CategoryList();
    myItemList = new ItemList(myDependencyManager);
    myAddToDesignAction = new AddToDesignAction();
    myFavoriteAction = new FavoriteAction();
    myAndroidDocAction = new ComponentHelpAction(project, this::getSelectedTagName);
    myMaterialDocAction = new MaterialDocAction();
    myActionGroup = createPopupActionGroup();

    myCategoryList.setBackground(StudioColorsKt.getSecondaryPanelBackground());
    myItemList.setBackground(StudioColorsKt.getSecondaryPanelBackground());

    myCategoryScrollPane = createScrollPane(myCategoryList);
    add(myCategoryScrollPane, BorderLayout.WEST);
    add(createScrollPane(myItemList), BorderLayout.CENTER);

    myFilterKeyListener = createFilterKeyListener();
    KeyListener keyListener = createKeyListener();

    myCategoryList.addListSelectionListener(event -> categorySelectionChanged());
    myCategoryList.setModel(myDataModel.getCategoryListModel());
    myCategoryList.addKeyListener(keyListener);
    myCategoryList.setBorder(JBUI.Borders.customLine(StudioColorsKt.getBorder(), 0, 0, 0, 1));

    PreviewProvider provider = new PreviewProvider(() -> myDesignSurface.get(), myDependencyManager);
    Disposer.register(this, provider);
    myItemList.setModel(myDataModel.getItemListModel());
    myItemList.setTransferHandler(new ItemTransferHandler(provider, myItemList::getSelectedValue));
    if (!GraphicsEnvironment.isHeadless()) {
      myItemList.setDragEnabled(true);
    }
    myItemList.addMouseListener(createItemListMouseListener());
    myItemList.addKeyListener(keyListener);
    registerKeyboardActions();

    myLayoutType = NlLayoutType.UNKNOWN;
    myLastSelectedGroup = DataModel.COMMON;
  }

  @NotNull
  @TestOnly
  AnAction getAddToDesignAction() {
    //noinspection ReturnOfInnerClass
    return myAddToDesignAction;
  }

  @NotNull
  @TestOnly
  AnAction getAndroidDocAction() {
    return myAndroidDocAction;
  }

  @NotNull
  @TestOnly
  AnAction getMaterialDocAction() {
    //noinspection ReturnOfInnerClass
    return myMaterialDocAction;
  }

  @NotNull
  private static JScrollPane createScrollPane(@NotNull JComponent component) {
    JScrollPane scrollPane = ScrollPaneFactory.createScrollPane(component, VERTICAL_SCROLLBAR_AS_NEEDED, HORIZONTAL_SCROLLBAR_NEVER);
    scrollPane.getVerticalScrollBar().setUnitIncrement(VERTICAL_SCROLLING_UNIT_INCREMENT);
    scrollPane.getVerticalScrollBar().setBlockIncrement(VERTICAL_SCROLLING_BLOCK_INCREMENT);
    scrollPane.setBorder(BorderFactory.createEmptyBorder());
    return scrollPane;
  }

  @NotNull
  private MouseListener createItemListMouseListener() {
    return new MouseAdapter() {
      @Override
      public void mousePressed(@NotNull MouseEvent event) {
        if (event.isPopupTrigger()) {
          showPopupMenu(event);
        }
      }

      // We really should handle mouseClick instead of mouseReleased events.
      // However on Mac with "Show Scroll bars: When scrolling" is set we are not receiving mouseClick events.
      // The mouseReleased events however are received every time we want to recognize the mouse click.
      @Override
      public void mouseReleased(@NotNull MouseEvent event) {
        if (event.isPopupTrigger()) {
          showPopupMenu(event);
        }
        else if (SwingUtilities.isLeftMouseButton(event) && !event.isControlDown()) {
          mouseClick(event);
        }
      }

      private void mouseClick(@NotNull MouseEvent event) {
        // b/111124139 On Windows the scrollbar width is included in myItemList.getWidth().
        // Use getCellBounds() instead if possible.
        Rectangle rect = myItemList.getCellBounds(0, 0);
        int width = rect != null ? rect.width : myItemList.getWidth();
        if (event.getX() < width - JBUIScale.scale(DOWNLOAD_WIDTH) || event.getX() >= myItemList.getWidth()) {
          // Ignore mouse clicks that are outside the download button
          return;
        }
        int index = myItemList.locationToIndex(event.getPoint());
        Palette.Item item = myItemList.getModel().getElementAt(index);
        myDependencyManager.ensureLibraryIsIncluded(item);
      }

      private void showPopupMenu(@NotNull MouseEvent event) {
        myItemList.setSelectedIndex(myItemList.locationToIndex(event.getPoint()));
        ActionPopupMenu popupMenu = ActionManager.getInstance().createActionPopupMenu(ActionPlaces.TOOLWINDOW_POPUP, myActionGroup);
        popupMenu.getComponent().show(myItemList, event.getX(), event.getY());
      }
    };
  }

  @NotNull
  private KeyListener createKeyListener() {
    return new KeyAdapter() {
      @Override
      public void keyTyped(@NotNull KeyEvent event) {
        if (event.getKeyChar() >= KeyEvent.VK_0 && myStartFilteringCallback != null) {
          myStartFilteringCallback.startFiltering(event.getKeyChar());
        }
      }
    };
  }

  private void registerKeyboardActions() {
    myItemList.registerKeyboardAction(event -> keyboardActionPerformed(event, myAddToDesignAction),
                                      KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0),
                                      JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT);

    myItemList.registerKeyboardAction(event -> keyboardActionPerformed(event, myAndroidDocAction),
                                      KeyStroke.getKeyStroke(KeyEvent.VK_F1, InputEvent.SHIFT_DOWN_MASK),
                                      JComponent.WHEN_ANCESTOR_OF_FOCUSED_COMPONENT);
  }

  private void keyboardActionPerformed(@NotNull ActionEvent event, @NotNull AnAction action) {
    DataContext dataContext = DataManager.getInstance().getDataContext(this);
    InputEvent inputEvent = event.getSource() instanceof InputEvent ? (InputEvent)event.getSource() : null;
    action.actionPerformed(AnActionEvent.createFromAnAction(action, inputEvent, ActionPlaces.TOOLWINDOW_POPUP, dataContext));
  }

  @NotNull
  private ActionGroup createPopupActionGroup() {
    DefaultActionGroup group = new DefaultActionGroup();
    group.add(myAddToDesignAction);
    group.add(myFavoriteAction);
    group.addSeparator();
    group.add(myAndroidDocAction);
    group.add(myMaterialDocAction);
    return group;
  }

  @Nullable
  private String getSelectedTagName() {
    Palette.Item item = myItemList.getSelectedValue();
    return item != null ? item.getTagName() : null;
  }

  private void categorySelectionChanged() {
    Palette.Group newSelection = myCategoryList.getSelectedValue();
    if (newSelection == null) {
      myLastSelectedGroup = DataModel.COMMON;
      myCategoryList.setSelectedIndex(0);
      return;
    }
    myDataModel.categorySelectionChanged(newSelection);
    myLastSelectedGroup = newSelection;
    myItemList.setSelectedIndex(0);
  }

  @NotNull
  @Override
  public JComponent getComponent() {
    return this;
  }

  @NotNull
  @Override
  public JComponent getFocusedComponent() {
    return myCategoryList;
  }

  @NotNull
  @VisibleForTesting
  public CategoryList getCategoryList() {
    return myCategoryList;
  }

  @NotNull
  @VisibleForTesting
  public ItemList getItemList() {
    return myItemList;
  }

  @Override
  public void requestFocus() {
    myCategoryList.requestFocus();
  }

  @Override
  public void setCloseAutoHideWindow(@NotNull Runnable runnable) {
    myCloseAutoHideCallback = runnable;
  }

  @Override
  public void setStartFiltering(@NotNull StartFilteringListener listener) {
    myStartFilteringCallback = listener;
  }

  @Override
  public void setStopFiltering(@NotNull Runnable runnable) {
    myStopFilteringCallback = runnable;
  }

  @Override
  public boolean supportsFiltering() {
    return true;
  }

  @Override
  public void setFilter(@NotNull String filter) {
    myDataModel.setFilterPattern(filter);
    Palette.Group newSelection = myDataModel.getCategoryListModel().contains(myLastSelectedGroup) ? myLastSelectedGroup : null;
    myCategoryList.clearSelection();
    myCategoryList.setSelectedValue(newSelection, true);
  }

  @Override
  @NotNull
  public KeyListener getFilterKeyListener() {
    return myFilterKeyListener;
  }

  @NotNull
  private KeyListener createFilterKeyListener() {
    return new KeyAdapter() {
      @Override
      public void keyPressed(@NotNull KeyEvent event) {
        if (myDataModel.hasFilterPattern() && event.getKeyCode() == KeyEvent.VK_ENTER && event.getModifiers() == 0 &&
            myItemList.getModel().getSize() == 1) {
          myItemList.requestFocus();
        }
      }
    };
  }

  @Override
  public void setToolContext(@Nullable DesignSurface designSurface) {
    assert designSurface == null || designSurface instanceof NlDesignSurface;
    Module module = getModule(designSurface);
    if (designSurface != null && module != null && myLayoutType != designSurface.getLayoutType()) {
      AndroidFacet facet = AndroidFacet.getInstance(module);
      assert facet != null;
      myLayoutType = designSurface.getLayoutType();
      myDataModel.setLayoutType(facet, myLayoutType);
      if (myDataModel.getCategoryListModel().hasExplicitGroups()) {
        setCategoryListVisible(true);
        myCategoryList.setSelectedIndex(0);
      }
      else {
        setCategoryListVisible(false);
        myDataModel.categorySelectionChanged(DataModel.COMMON);
        myItemList.setSelectedIndex(0);
      }
    }
    myDesignSurface = new WeakReference<>(designSurface);
  }

  private void setCategoryListVisible(boolean visible) {
    myCategoryScrollPane.setVisible(visible);
  }

  @Nullable
  private static Module getModule(@Nullable DesignSurface designSurface) {
    Configuration configuration =
      designSurface != null && designSurface.getLayoutType().isSupportedByDesigner() ? designSurface.getConfiguration() : null;
    return configuration != null ? configuration.getModule() : null;
  }

  @Override
  public void dispose() {
  }

  @Nullable
  @Override
  public Object getData(@NotNull @NonNls String dataId) {
    return PlatformDataKeys.COPY_PROVIDER.is(dataId) ? myCopyProvider : null;
  }

  private class CopyProviderImpl implements CopyProvider {

    @Override
    public void performCopy(@NotNull DataContext dataContext) {
      Palette.Item item = myItemList.getSelectedValue();
      if (item != null && !myDependencyManager.needsLibraryLoad(item)) {
        DnDTransferComponent component = new DnDTransferComponent(item.getTagName(), item.getXml(), 0, 0);
        CopyPasteManager.getInstance().setContents(new ItemTransferable(new DnDTransferItem(component)));
      }
    }

    @Override
    public boolean isCopyEnabled(@NotNull DataContext dataContext) {
      Palette.Item item = myItemList.getSelectedValue();
      return item != null && !myDependencyManager.needsLibraryLoad(item);
    }

    @Override
    public boolean isCopyVisible(@NotNull DataContext dataContext) {
      return true;
    }
  }

  private class ItemTransferHandler extends TransferHandler {
    private final PreviewProvider myPreviewProvider;
    private final Supplier<Palette.Item> myItemSupplier;

    private ItemTransferHandler(@NotNull PreviewProvider provider,
                                @NotNull Supplier<Palette.Item> itemSupplier) {
      myPreviewProvider = provider;
      myItemSupplier = itemSupplier;
    }

    @Override
    public int getSourceActions(@NotNull JComponent component) {
      return DnDConstants.ACTION_COPY_OR_MOVE;
    }

    @Override
    @Nullable
    protected Transferable createTransferable(@NotNull JComponent component) {
      Palette.Item item = myItemSupplier.get();
      if (item == null) {
        return null;
      }
      DumbService dumbService = DumbService.getInstance(myProject);
      if (dumbService.isDumb()) {
        dumbService.showDumbModeNotification("Dragging from the Palette is not available while indices are updating.");
        return null;
      }

      PreviewProvider.ImageAndDimension imageAndSize = myPreviewProvider.createPreview(component, item);
      BufferedImage image = imageAndSize.image;
      Dimension size = imageAndSize.dimension;
      setDragImage(image);
      setDragImageOffset(new Point(-image.getWidth() / 2, -image.getHeight() / 2));
      DnDTransferComponent dndComponent = new DnDTransferComponent(item.getTagName(), item.getXml(), size.width, size.height);
      Transferable transferable = new ItemTransferable(new DnDTransferItem(dndComponent));

      if (myCloseAutoHideCallback != null) {
        myCloseAutoHideCallback.run();
      }
      return transferable;
    }

    @Override
    protected void exportDone(@NotNull JComponent source, @Nullable Transferable data, int action) {
      if (action == DnDConstants.ACTION_NONE || data == null) {
        return;
      }
      DnDTransferComponent component = getDndComponent(data);
      if (component == null) {
        return;
      }
      if (myStopFilteringCallback != null) {
        myStopFilteringCallback.run();
      }
      NlUsageTrackerManager.getInstance(myDesignSurface.get()).logDropFromPalette(
        component.getTag(), component.getRepresentation(), getGroupName(), myDataModel.getMatchCount());
    }

    @NotNull
    private String getGroupName() {
      Palette.Group group = myCategoryList.getSelectedValue();
      return group != null ? group.getName() : "";
    }

    @Nullable
    private DnDTransferComponent getDndComponent(@NotNull Transferable data) {
      try {
        DnDTransferItem item = (DnDTransferItem)data.getTransferData(ItemTransferable.DESIGNER_FLAVOR);
        if (item != null) {
          List<DnDTransferComponent> components = item.getComponents();
          if (components.size() == 1) {
            return components.get(0);
          }
        }
      }
      catch (UnsupportedFlavorException | IOException ex) {
        Logger.getInstance(PalettePanel.class).warn("Could not un-serialize a transferable", ex);
      }
      return null;
    }
  }

  private class AddToDesignAction extends AnAction {

    AddToDesignAction() {
      super("Add to Design");
      setShortcutSet(new CustomShortcutSet(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0)));
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent event) {
      addComponentToModel(false /* checkOnly */);
    }

    @Override
    public void update(@NotNull AnActionEvent event) {
      event.getPresentation().setEnabled(addComponentToModel(true /* checkOnly */));
    }

    private boolean addComponentToModel(boolean checkOnly) {
      Palette.Item item = myItemList.getSelectedValue();
      if (item == null) {
        return false;
      }
      DesignSurface surface = myDesignSurface.get();
      if (surface == null) {
        return false;
      }
      NlModel model = surface.getModel();
      if (model == null) {
        return false;
      }
      List<NlComponent> roots = model.getComponents();
      if (roots.isEmpty()) {
        return false;
      }
      SceneView sceneView = surface.getCurrentSceneView();
      if (sceneView == null) {
        return false;
      }
      DnDTransferComponent dndComponent = new DnDTransferComponent(item.getTagName(), item.getXml(), 0, 0);
      DnDTransferItem dndItem = new DnDTransferItem(dndComponent);
      InsertType insertType = model.determineInsertType(DragType.COPY, dndItem, checkOnly /* preview */);

      List<NlComponent> toAdd = model.createComponents(dndItem, insertType, surface);

      NlComponent root = roots.get(0);
      if (!model.canAddComponents(toAdd, root, null, checkOnly)) {
        return false;
      }
      if (!checkOnly) {
        model.addComponents(toAdd, root, null, insertType, sceneView.getSurface());
        surface.getSelectionModel().setSelection(toAdd);
        surface.getLayeredPane().requestFocus();
      }
      return true;
    }
  }

  private class FavoriteAction extends ToggleAction {

    FavoriteAction() {
      super("Favorite");
    }

    @Override
    public boolean isSelected(@NotNull AnActionEvent event) {
      Palette.Item item = myItemList.getSelectedValue();
      return item != null && myDataModel.isFavoriteItem(item);
    }

    @Override
    public void setSelected(@NotNull AnActionEvent event, boolean state) {
      Palette.Item item = myItemList.getSelectedValue();
      if (item != null) {
        if (state) {
          myDataModel.addFavoriteItem(item);
        }
        else {
          myDataModel.removeFavoriteItem(item);
        }
      }
    }
  }

  private class MaterialDocAction extends AnAction {
    private static final String MATERIAL_DEFAULT_REFERENCE = "https://material.io/guidelines/material-design/introduction.html";

    MaterialDocAction() {
      super("Material Guidelines");
    }

    @Override
    public void actionPerformed(@NotNull AnActionEvent event) {
      String reference = getReference();
      if (!reference.isEmpty()) {
        BrowserUtil.browse(reference);
      }
    }

    @Override
    public void update(@NotNull AnActionEvent event) {
      event.getPresentation().setEnabled(!getReference().isEmpty());
    }

    private String getReference() {
      Palette.Item item = myItemList.getSelectedValue();
      if (item == null) {
        return "";
      }
      String reference = item.getMaterialReference();
      if (reference == null) {
        reference = MATERIAL_DEFAULT_REFERENCE;
      }
      return StringUtil.notNullize(reference);
    }
  }
}
