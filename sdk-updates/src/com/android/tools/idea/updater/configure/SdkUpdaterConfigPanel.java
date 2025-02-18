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
package com.android.tools.idea.updater.configure;

import com.android.repository.api.*;
import com.android.repository.api.RepoManager.RepoLoadedCallback;
import com.android.repository.impl.meta.RepositoryPackages;
import com.android.repository.impl.meta.TypeDetails;
import com.android.sdklib.AndroidVersion;
import com.android.sdklib.repository.meta.DetailsTypes;
import com.android.tools.analytics.UsageTracker;
import com.android.tools.idea.gradle.util.LocalProperties;
import com.android.tools.idea.npw.PathValidationResult;
import com.android.tools.idea.npw.PathValidationResult.WritableCheckMode;
import com.android.tools.idea.observable.BindingsManager;
import com.android.tools.idea.observable.adapters.AdapterProperty;
import com.android.tools.idea.observable.core.OptionalValueProperty;
import com.android.tools.idea.observable.ui.TextProperty;
import com.android.tools.idea.sdk.IdeSdks;
import com.android.tools.idea.sdk.progress.StudioProgressRunner;
import com.android.tools.idea.ui.ApplicationUtils;
import com.android.tools.idea.welcome.config.FirstRunWizardMode;
import com.android.tools.idea.welcome.install.FirstRunWizardDefaults;
import com.android.tools.idea.welcome.wizard.deprecated.ConsolidatedProgressStep;
import com.android.tools.idea.welcome.wizard.deprecated.InstallComponentsPath;
import com.android.tools.idea.wizard.WizardConstants;
import com.android.tools.idea.wizard.dynamic.DialogWrapperHost;
import com.android.tools.idea.wizard.dynamic.DynamicWizard;
import com.android.tools.idea.wizard.dynamic.DynamicWizardHost;
import com.android.tools.idea.wizard.dynamic.SingleStepPath;
import com.google.common.collect.Lists;
import com.google.common.collect.Multimap;
import com.google.common.collect.Sets;
import com.google.common.collect.TreeMultimap;
import com.google.wireless.android.sdk.stats.AndroidStudioEvent;
import com.google.wireless.android.sdk.stats.AndroidStudioEvent.EventCategory;
import com.google.wireless.android.sdk.stats.AndroidStudioEvent.EventKind;
import com.intellij.facet.ProjectFacetManager;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.Disposable;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.application.ModalityState;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.options.Configurable;
import com.intellij.openapi.options.ShowSettingsUtil;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.project.ProjectManager;
import com.intellij.openapi.updateSettings.impl.UpdateSettingsConfigurable;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.openapi.util.text.StringUtil;
import com.intellij.ui.HyperlinkAdapter;
import com.intellij.ui.HyperlinkLabel;
import com.intellij.ui.JBColor;
import com.intellij.ui.components.JBLabel;
import com.intellij.ui.components.JBTabbedPane;
import com.intellij.ui.dualView.TreeTableView;
import com.intellij.ui.table.SelectionProvider;
import java.util.HashSet;
import com.intellij.util.containers.ImmutableList;
import com.intellij.util.ui.accessibility.ScreenReader;
import com.intellij.util.ui.tree.TreeUtil;
import org.jetbrains.android.facet.AndroidFacet;
import org.jetbrains.android.sdk.AndroidSdkData;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import sun.awt.CausedFocusEvent;

import javax.swing.*;
import javax.swing.event.ChangeEvent;
import javax.swing.event.ChangeListener;
import javax.swing.event.HyperlinkEvent;
import javax.swing.table.TableCellRenderer;
import javax.swing.table.TableColumnModel;
import java.awt.*;
import java.awt.event.*;
import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.*;

import static com.android.tools.idea.npw.PathValidationResult.validateLocation;

/**
 * Main panel for {@link SdkUpdaterConfigurable}
 */
public class SdkUpdaterConfigPanel implements Disposable {
  /**
   * Main panel for the SDK configurable.
   */
  private JPanel myRootPane;

  /**
   * "Android SDK Location" text box at the top, used in the single-SDK case (this is always the case in Studio).
   */
  private JTextField mySdkLocationTextField;

  /**
   * SDK Location chooser for the multi-SDK case (for non-gradle projects in IJ).
   */
  private JComboBox<File> mySdkLocationChooser;

  /**
   * Label for SDK location.
   */
  private JBLabel mySdkLocationLabel;

  /**
   * Panel for switching between the multi- and single-SDK cases.
   */
  private JPanel mySdkLocationPanel;

  /**
   * Link to allow you to edit the SDK location.
   */
  private HyperlinkLabel myEditSdkLink;

  /**
   * Error message that shows if the selected SDK location is invalid.
   */
  private JBLabel mySdkErrorLabel;

  /**
   * Panel showing platform-specific components.
   */
  private PlatformComponentsPanel myPlatformComponentsPanel;

  /**
   * Panel showing non-platform-specific components.
   */
  private ToolComponentsPanel myToolComponentsPanel;

  /**
   * Panel showing what remote sites are checked for updates and new components.
   */
  private UpdateSitesPanel myUpdateSitesPanel;

  /**
   * Link to let you switch to the preview channel if there are previews available.
   */
  private HyperlinkLabel myChannelLink;

  /**
   * Tab pane containing {@link #myPlatformComponentsPanel}, {@link #myToolComponentsPanel}, and {@link #myUpdateSitesPanel}.
   */
  private JBTabbedPane myTabPane;

  /**
   * {@link Downloader} for fetching remote source lists and packages.
   */
  private final Downloader myDownloader;

  /**
   * Settings for the downloader.
   */
  private final SettingsController mySettings;

  /**
   * Reference to the {@link Configurable} that created us, for retrieving SDK state.
   */
  private final SdkUpdaterConfigurable myConfigurable;

  private final OptionalValueProperty<File> mySelectedSdkLocation = new OptionalValueProperty<>();

  private final BindingsManager myBindingsManager = new BindingsManager();

  /**
   * {@link RepoLoadedCallback} that runs when we've finished reloading our local packages.
   */
  private final RepoLoadedCallback myLocalUpdater = packages -> ApplicationManager.getApplication().invokeLater(
    () -> loadPackages(packages), ModalityState.any());

  /**
   * {@link RepoLoadedCallback} that runs when we've completely finished reloading our packages.
   */
  private final RepoLoadedCallback myRemoteUpdater = new RepoLoadedCallback() {
    @Override
    public void doRun(@NotNull final RepositoryPackages packages) {
      ApplicationManager.getApplication().invokeLater(() -> {
        loadPackages(packages);
        myPlatformComponentsPanel.finishLoading();
        myToolComponentsPanel.finishLoading();
      }, ModalityState.any());
    }
  };

  /**
   * Construct a new SdkUpdaterConfigPanel.
   *
   * @param channelChangedCallback Callback to allow us to notify the channel picker panel if we change the selected channel.
   * @param downloader             {@link Downloader} to download remote site lists and for installing packages. If {@code null} we will
   *                               only show local packages.
   * @param settings               {@link SettingsController} for e.g. proxy settings.
   * @param configurable           The {@link SdkUpdaterConfigurable} that created this.
   */
  public SdkUpdaterConfigPanel(@NotNull final Runnable channelChangedCallback,
                               @Nullable Downloader downloader,
                               @Nullable SettingsController settings,
                               @NotNull SdkUpdaterConfigurable configurable) {
    UsageTracker.log(AndroidStudioEvent.newBuilder()
                                     .setCategory(EventCategory.SDK_MANAGER)
                                     .setKind(EventKind.SDK_MANAGER_LOADED));

    myConfigurable = configurable;
    myUpdateSitesPanel.setConfigurable(configurable);
    myDownloader = downloader;
    mySettings = settings;

    Collection<File> sdkLocations = getSdkLocations();
    if (!sdkLocations.isEmpty()) {
      mySelectedSdkLocation.set(sdkLocations.stream().findFirst());
    }
    mySelectedSdkLocation.addListener(sender -> ApplicationManager.getApplication().invokeLater(this::reset));

    ((CardLayout)mySdkLocationPanel.getLayout()).show(mySdkLocationPanel, "SingleSdk");
    setUpSingleSdkChooser();
    myBindingsManager.bindTwoWay(
      mySelectedSdkLocation,
      new AdapterProperty<String, Optional<File>>(new TextProperty(mySdkLocationTextField), mySelectedSdkLocation.get()) {
        @Nullable
        @Override
        protected Optional<File> convertFromSourceType(@NotNull String value) {
          if (value.isEmpty()) {
            return Optional.empty();
          }
          return Optional.of(new File(value));
        }

        @SuppressWarnings("OptionalUsedAsFieldOrParameterType")
        @NotNull
        @Override
        protected String convertFromDestType(@NotNull Optional<File> value) {
          return value.isPresent() ? value.get().getPath() : "";
        }
      });

    myChannelLink.setHyperlinkText("Preview packages available! ", "Switch", " to Preview Channel to see them");
    myChannelLink.addHyperlinkListener(new HyperlinkAdapter() {
      @Override
      protected void hyperlinkActivated(HyperlinkEvent e) {
        UpdateSettingsConfigurable settings = new UpdateSettingsConfigurable(false);
        ShowSettingsUtil.getInstance().editConfigurable(getComponent(), settings);
        channelChangedCallback.run();
      }
    });

    myToolComponentsPanel.setConfigurable(myConfigurable);
    myPlatformComponentsPanel.setConfigurable(myConfigurable);
  }

  /**
   * @return The path to the current sdk to show, or {@code null} if none is selected.
   */
  @Nullable
  File getSelectedSdkLocation() {
    return mySelectedSdkLocation.get().orElse(null);
  }

  @NotNull
  private static Collection<File> getSdkLocations() {
    File androidHome = IdeSdks.getInstance().getAndroidSdkPath();
    if (androidHome != null) {
      return ImmutableList.singleton(androidHome);
    }

    Set<File> locations = new HashSet<>();
    // We don't check Projects.isGradleProject(project) because it may return false if the last sync failed, even if it is a
    // Gradle project.
    for (Project project : ProjectManager.getInstance().getOpenProjects()) {
      try {
        LocalProperties localProperties = new LocalProperties(project);
        File androidSdkPath = localProperties.getAndroidSdkPath();
        if (androidSdkPath != null) {
          locations.add(androidSdkPath);
          continue;
        }
      }
      catch (IOException ignored) {
        Logger.getInstance(SdkUpdaterConfigPanel.class)
          .info("Unable to read local.properties file from project: " + project.getName(), ignored);
      }

      List<AndroidFacet> facets = ProjectFacetManager.getInstance(project).getFacets(AndroidFacet.ID);

      for (AndroidFacet facet : facets) {
        AndroidSdkData sdkData = facet.getConfiguration().getAndroidSdk();
        if (sdkData != null) {
          locations.add(sdkData.getLocation());
        }
      }
    }
    return locations;
  }

  private void setUpSingleSdkChooser() {
    myEditSdkLink.setHyperlinkText("Edit");
    myEditSdkLink.addHyperlinkListener(new HyperlinkAdapter() {
      @Override
      protected void hyperlinkActivated(HyperlinkEvent e) {
        final DynamicWizardHost host = new DialogWrapperHost(null);
        DynamicWizard wizard = new DynamicWizard(null, null, "SDK Setup", host) {
          @Override
          public void init() {
            DownloadingComponentsStep progressStep = new DownloadingComponentsStep(myHost.getDisposable(), myHost);

            String sdkPath = mySdkLocationTextField.getText();
            File location;
            if (StringUtil.isEmpty(sdkPath)) {
              location = FirstRunWizardDefaults.getInitialSdkLocation(FirstRunWizardMode.MISSING_SDK);
            }
            else {
              location = new File(sdkPath);
            }

            InstallComponentsPath path =
              new InstallComponentsPath(FirstRunWizardMode.MISSING_SDK, location, progressStep, false);

            progressStep.setInstallComponentsPath(path);

            addPath(path);
            addPath(new SingleStepPath(progressStep));
            super.init();
          }

          @Override
          public void performFinishingActions() {
            File sdkLocation = IdeSdks.getInstance().getAndroidSdkPath();

            if (sdkLocation == null) {
              return;
            }

            String stateSdkLocationPath = myState.get(WizardConstants.KEY_SDK_INSTALL_LOCATION);
            assert stateSdkLocationPath != null;

            File stateSdkLocation = new File(stateSdkLocationPath);

            if (!FileUtil.filesEqual(sdkLocation, stateSdkLocation)) {
              setAndroidSdkLocation(stateSdkLocation);
              sdkLocation = stateSdkLocation;
            }

            mySelectedSdkLocation.setValue(sdkLocation);
          }

          @NotNull
          @Override
          protected String getProgressTitle() {
            return "Setting up SDK...";
          }

          @Override
          protected String getWizardActionDescription() {
            return "Setting up SDK...";
          }
        };
        wizard.init();
        wizard.show();
      }
    });
    mySdkLocationTextField.setEditable(false);
  }

  @Override
  public void dispose() {
    myBindingsManager.releaseAll();
  }

  private static final class DownloadingComponentsStep extends ConsolidatedProgressStep {
    private InstallComponentsPath myInstallComponentsPath;

    private DownloadingComponentsStep(@NotNull Disposable disposable, @NotNull DynamicWizardHost host) {
      super(disposable, host);
    }

    private void setInstallComponentsPath(InstallComponentsPath installComponentsPath) {
      setPaths(Collections.singletonList(installComponentsPath));
      myInstallComponentsPath = installComponentsPath;
    }

    @Override
    public boolean isStepVisible() {
      return myInstallComponentsPath.shouldDownloadingComponentsStepBeShown();
    }
  }

  private static void setAndroidSdkLocation(final File sdkLocation) {
    ApplicationUtils.invokeWriteActionAndWait(ModalityState.any(), () -> {
      // TODO Do we have to pass the default project here too instead of null?
      IdeSdks.getInstance().setAndroidSdkPath(sdkLocation, null);
    });
  }

  /**
   * Gets our main component. Useful for e.g. creating modal dialogs that need to show on top of this (that is, with this as the parent).
   */
  public JComponent getComponent() {
    return myRootPane;
  }

  /**
   * @return {@code true} if the user has made any changes, and the "apply" button should be active and "Reset" link shown.
   */
  public boolean isModified() {
    return myPlatformComponentsPanel.isModified() || myToolComponentsPanel.isModified() || myUpdateSitesPanel.isModified();
  }

  /**
   * Sets the standard properties for our {@link TreeTableView}s (platform and tools panels).
   *
   * @param tt       The {@link TreeTableView} for which to set properties.
   * @param renderer The {@link UpdaterTreeNode.Renderer} that renders the table.
   * @param listener {@link ChangeListener} to be notified when a node's state is changed.
   */
  static void setTreeTableProperties(final TreeTableView tt, UpdaterTreeNode.Renderer renderer, final ChangeListener listener) {
    tt.setTreeCellRenderer(renderer);
    new CheckboxClickListener(tt, renderer).installOn(tt);
    TreeUtil.installActions(tt.getTree());

    tt.getTree().setToggleClickCount(0);
    tt.getTree().setShowsRootHandles(true);

    setTableProperties(tt, listener);
  }

  /**
   * Sets the standard properties for our {@link JTable}s (platform, tools, and sources panels).
   *
   * @param table    The {@link JTable} for which to set properties.
   * @param listener {@link ChangeListener} to be notified when a node's state is changed.
   */
  static void setTableProperties(@NotNull final JTable table, @Nullable final ChangeListener listener) {
    assert table instanceof SelectionProvider;
    ActionMap am = table.getActionMap();
    final CycleAction forwardAction = new CycleAction(false);
    final CycleAction backwardAction = new CycleAction(true);

    // With a screen reader, we need to let the user navigate through all the
    // cells so that they can be read, so don't override the prev/next cell actions.
    if (!ScreenReader.isActive()) {
      am.put("selectPreviousColumnCell", backwardAction);
      am.put("selectNextColumnCell", forwardAction);
    }

    table.addKeyListener(new KeyAdapter() {
      @Override
      public void keyTyped(KeyEvent e) {
        if (e.getKeyChar() == KeyEvent.VK_ENTER || e.getKeyChar() == KeyEvent.VK_SPACE) {
          @SuppressWarnings("unchecked") Iterable<MultiStateRow> selection =
            (Iterable<MultiStateRow>)((SelectionProvider)table).getSelection();

          for (MultiStateRow node : selection) {
            node.cycleState();
            table.repaint();
            if (listener != null) {
              listener.stateChanged(new ChangeEvent(node));
            }
          }
        }
      }
    });
    table.addFocusListener(new FocusListener() {
      @Override
      public void focusLost(FocusEvent e) {
        if (e.getOppositeComponent() != null) {
          table.getSelectionModel().clearSelection();
        }
      }

      @Override
      public void focusGained(FocusEvent e) {
        JTable table = (JTable)e.getSource();
        if (table.getSelectionModel().getMinSelectionIndex() != -1) {
          return;
        }
        if (e instanceof CausedFocusEvent && ((CausedFocusEvent)e).getCause() == CausedFocusEvent.Cause.TRAVERSAL_BACKWARD) {
          backwardAction.doAction(table);
        }
        else {
          forwardAction.doAction(table);
        }
      }
    });
  }

  /**
   * Helper to size a table's columns to fit their normal contents.
   */
  protected static void resizeColumnsToFit(JTable table) {
    TableColumnModel columnModel = table.getColumnModel();
    for (int column = 1; column < table.getColumnCount(); column++) {
      int width = 50;
      for (int row = 0; row < table.getRowCount(); row++) {
        TableCellRenderer renderer = table.getCellRenderer(row, column);
        Component comp = table.prepareRenderer(renderer, row, column);
        width = Math.max(comp.getPreferredSize().width + 1, width);
      }
      columnModel.getColumn(column).setPreferredWidth(width);
    }
  }

  /**
   * Revalidates and refreshes our packages. Notifies platform and tools components of the start and end, so they can update their UIs.
   */
  public void refresh(boolean forceRemoteReload) {
    validate();

    // TODO: make progress runner handle invokes?
    Project[] projects = ProjectManager.getInstance().getOpenProjects();
    StudioProgressRunner progressRunner =
      new StudioProgressRunner(false, false, "Loading SDK", projects.length == 0 ? null : projects[0]);
    if (forceRemoteReload) {
      myPlatformComponentsPanel.startLoading();
      myToolComponentsPanel.startLoading();
      myConfigurable.getRepoManager()
                    .load(0, ImmutableList.singleton(myLocalUpdater), ImmutableList.singleton(myRemoteUpdater), null,
                          progressRunner, myDownloader, mySettings, false);
    }
    else {
      myConfigurable.getRepoManager()
                    .load(0, ImmutableList.singleton(myLocalUpdater), null, null,
                          progressRunner, null, mySettings, false);
    }
  }

  /**
   * Validates {@link #mySdkLocationTextField} and shows appropriate errors in the UI if needed.
   */
  private void validate() {
    File sdkLocation = myConfigurable.getRepoManager().getLocalPath();
    String sdkLocationPath = sdkLocation == null ? null : sdkLocation.getAbsolutePath();

    PathValidationResult result =
      validateLocation(sdkLocationPath, "Android SDK location", false, WritableCheckMode.NOT_WRITABLE_IS_WARNING);

    switch (result.getStatus()) {
      case OK:
        mySdkLocationLabel.setForeground(JBColor.foreground());
        mySdkErrorLabel.setVisible(false);
        myPlatformComponentsPanel.setEnabled(true);
        myTabPane.setEnabled(true);

        break;
      case WARN:
        mySdkErrorLabel.setIcon(AllIcons.General.BalloonWarning);
        mySdkErrorLabel.setText(result.getFormattedMessage());
        mySdkErrorLabel.setVisible(true);

        myPlatformComponentsPanel.setEnabled(true);
        myTabPane.setEnabled(true);

        break;
      case ERROR:
        mySdkErrorLabel.setIcon(AllIcons.General.BalloonError);
        mySdkErrorLabel.setText(result.getFormattedMessage());
        mySdkErrorLabel.setVisible(true);

        myPlatformComponentsPanel.setEnabled(false);
        myTabPane.setEnabled(false);

        break;
    }
  }

  private void loadPackages(RepositoryPackages packages) {
    Multimap<AndroidVersion, UpdatablePackage> platformPackages = TreeMultimap.create();
    Set<UpdatablePackage> toolsPackages = Sets.newTreeSet();
    for (UpdatablePackage info : packages.getConsolidatedPkgs().values()) {
      RepoPackage p = info.getRepresentative();
      TypeDetails details = p.getTypeDetails();
      if (details instanceof DetailsTypes.ApiDetailsType) {
        platformPackages.put(((DetailsTypes.ApiDetailsType)details).getAndroidVersion(), info);
      }
      else {
        toolsPackages.add(info);
      }
    }
    // TODO: when should we show this?
    //myChannelLink.setVisible(myHasPreview && !myIncludePreview);
    myPlatformComponentsPanel.setPackages(platformPackages);
    myToolComponentsPanel.setPackages(toolsPackages);
  }

  /**
   * Gets the consolidated list of {@link PackageNodeModel}s from our children so they can be applied.
   *
   * @return
   */
  public Collection<PackageNodeModel> getStates() {
    List<PackageNodeModel> result = Lists.newArrayList();
    result.addAll(myPlatformComponentsPanel.myStates);
    result.addAll(myToolComponentsPanel.myStates);
    return result;
  }

  /**
   * Resets our state back to what it was before the user made any changes.
   */
  public void reset() {
    refresh(true);
    Collection<File> sdkLocations = getSdkLocations();
    if (getSdkLocations().size() == 1) {
      mySdkLocationTextField.setText(sdkLocations.iterator().next().getPath());
    }
    myPlatformComponentsPanel.reset();
    myToolComponentsPanel.reset();
    myUpdateSitesPanel.reset();
  }

  /**
   * Save any changes to our {@link RepositorySource}s.
   */
  public void saveSources() {
    myUpdateSitesPanel.save();
  }

  /**
   * Checks whether there have been any changes made to {@link RepositorySource}s via UI.
   */
  public boolean areSourcesModified() {
    return myUpdateSitesPanel.isModified();
  }

  /**
   * Create our UI components that need custom creation.
   */
  private void createUIComponents() {
    myUpdateSitesPanel = new UpdateSitesPanel(() -> refresh(true));
  }

  /**
   * Generic action to cycle through the rows in a table, either forward or backward.
   */
  private static class CycleAction extends AbstractAction {
    final boolean myBackward;

    CycleAction(boolean backward) {
      myBackward = backward;
    }

    @Override
    public void actionPerformed(ActionEvent evt) {
      doAction((JTable)evt.getSource());
    }

    public void doAction(JTable table) {
      KeyboardFocusManager manager = KeyboardFocusManager.getCurrentKeyboardFocusManager();
      ListSelectionModel selectionModel = table.getSelectionModel();
      int row = myBackward ? selectionModel.getMinSelectionIndex() : selectionModel.getMaxSelectionIndex();

      if (row == -1) {
        if (myBackward) {
          row = table.getRowCount();
        }
      }
      row += myBackward ? -1 : 1;
      if (row < 0) {
        manager.focusPreviousComponent(table);
      }
      else if (row >= table.getRowCount()) {
        manager.focusNextComponent(table);
      }
      else {
        selectionModel.setSelectionInterval(row, row);
        table.setColumnSelectionInterval(1, 1);
        table.scrollRectToVisible(table.getCellRect(row, 1, true));
      }
      table.repaint();
    }
  }

  /**
   * Convenience to allow us to easily and consistently implement keyboard accessibility features on our tables.
   */
  public interface MultiStateRow {
    void cycleState();
  }
}
