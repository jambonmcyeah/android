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
package com.android.tools.idea.res;

import com.android.annotations.NonNull;
import com.android.annotations.Nullable;
import com.android.ide.common.rendering.api.ResourceNamespace;
import com.android.ide.common.resources.ResourceItem;
import com.android.ide.common.resources.ResourceTable;
import com.android.ide.common.resources.SingleNamespaceResourceRepository;
import com.android.resources.ResourceType;
import com.android.tools.idea.projectsystem.ProjectSystemUtil;
import com.android.tools.idea.sampledata.datasource.*;
import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.ListMultimap;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.util.Key;
import com.intellij.openapi.vfs.*;
import com.intellij.psi.*;
import org.jetbrains.android.facet.AndroidFacet;
import org.jetbrains.android.facet.AndroidFacetScopedService;
import org.jetbrains.annotations.NotNull;

import javax.annotation.concurrent.GuardedBy;
import java.io.IOException;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.stream.Stream;

import static com.android.tools.idea.res.SampleDataResourceItem.ContentType.IMAGE;
import static com.android.tools.idea.res.SampleDataResourceItem.ContentType.TEXT;
import static com.android.tools.idea.util.FileExtensions.toVirtualFile;

/**
 * A {@link LocalResourceRepository} that provides sample data to be used within "tools" attributes. This provider
 * defines a set of predefined sources that are always available but also allows to define new data sources in the project.
 * To define new project data sources a new file of folder needs to be created under the sampledata folder in the project
 * root.
 * The repository provides access to the full contents of the data sources. Selection of items is done by the
 * {@link com.android.ide.common.resources.sampledata.SampleDataManager}
 * <p/>
 * The {@link SampleDataResourceRepository} currently supports 3 data formats:
 * <ul>
 *   <li><b>Plain files</b>: Files that allow defining a new item per line
 *   <li><b>JSON files</b>: The SampleDataResourceRepository will extract every possible path that gives access to an array of
 *   elements and provide access to them
 *   <li><b>Directories</b>: Folders that contain a list of images
 * </ul>
 */
public class SampleDataResourceRepository extends LocalResourceRepository implements SingleNamespaceResourceRepository {
  public static final ResourceNamespace PREDEFINED_SAMPLES_NS = ResourceNamespace.TOOLS;

  /**
   * List of predefined data sources that are always available within studio
   */
  private static final ImmutableList<SampleDataResourceItem> PREDEFINED_SOURCES = ImmutableList.of(
    SampleDataResourceItem.getFromStaticDataSource("full_names", new CombinerDataSource(
                                                     SampleDataResourceRepository.class.getClassLoader().getResourceAsStream("sampleData/names.txt"),
                                                     SampleDataResourceRepository.class.getClassLoader().getResourceAsStream("sampleData/surnames.txt")),
                                                   TEXT),
    SampleDataResourceItem.getFromStaticDataSource("first_names", ResourceContent.fromInputStream(
      SampleDataResourceRepository.class.getClassLoader().getResourceAsStream("sampleData/names.txt")),
                                                   TEXT),
    SampleDataResourceItem.getFromStaticDataSource("last_names", ResourceContent.fromInputStream(
      SampleDataResourceRepository.class.getClassLoader()
        .getResourceAsStream("sampleData/surnames.txt")),
                                                   TEXT),
    SampleDataResourceItem.getFromStaticDataSource("cities", ResourceContent.fromInputStream(
      SampleDataResourceRepository.class.getClassLoader()
        .getResourceAsStream("sampleData/cities.txt")),
                                                   TEXT),
    SampleDataResourceItem.getFromStaticDataSource("us_zipcodes",
                                                   new NumberGenerator("%05d", 20000, 99999),
                                                   TEXT),
    SampleDataResourceItem.getFromStaticDataSource("us_phones",
                                                   new NumberGenerator("(800) 555-%04d", 0, 9999),
                                                   TEXT),
    SampleDataResourceItem.getFromStaticDataSource("lorem", new LoremIpsumGenerator(false),
                                                   TEXT),
    SampleDataResourceItem.getFromStaticDataSource("lorem/random", new LoremIpsumGenerator(true),
                                                   TEXT),
    SampleDataResourceItem.getFromStaticDataSource("avatars",
                                                   ResourceContent.fromDirectory("avatars"),
                                                   IMAGE),
    SampleDataResourceItem.getFromStaticDataSource("backgrounds/scenic",
                                                   ResourceContent.fromDirectory("backgrounds/scenic"),
                                                   IMAGE),

    // TODO: Delegate path parsing to the data source to avoid all these declarations
    SampleDataResourceItem.getFromStaticDataSource("date/day_of_week",
                                                   new DateTimeGenerator(DateTimeFormatter.ofPattern("E"), ChronoUnit.DAYS),
                                                   TEXT
    ),
    SampleDataResourceItem.getFromStaticDataSource("date/ddmmyy",
                                                   new DateTimeGenerator(DateTimeFormatter.ofPattern("dd-MM-yy"), ChronoUnit.DAYS),
                                                   TEXT
    ),
    SampleDataResourceItem.getFromStaticDataSource("date/mmddyy",
                                                   new DateTimeGenerator(DateTimeFormatter.ofPattern("MM-dd-yy"), ChronoUnit.DAYS),
                                                   TEXT
    ),
    SampleDataResourceItem.getFromStaticDataSource("date/hhmm",
                                                   new DateTimeGenerator(DateTimeFormatter.ofPattern("hh:mm"), ChronoUnit.MINUTES),
                                                   TEXT
    ),
    SampleDataResourceItem.getFromStaticDataSource("date/hhmmss",
                                                   new DateTimeGenerator(DateTimeFormatter.ofPattern("hh:mm:ss"), ChronoUnit.SECONDS),
                                                   TEXT
    ));


  private final ResourceTable myFullTable;
  private AndroidFacet myAndroidFacet;

  @NotNull
  public static SampleDataResourceRepository getInstance(@NotNull AndroidFacet facet) {
    return SampleDataRepositoryManager.getInstance(facet).getRepository();
  }

  private SampleDataResourceRepository(@NotNull AndroidFacet androidFacet) {
    super("SampleData");

    Disposer.register(androidFacet, this);

    myFullTable = new ResourceTable();
    myAndroidFacet = androidFacet;

    SampleDataListener.ensureSubscribed(androidFacet.getModule().getProject());

    invalidate();
  }

  private void addItems(@NotNull PsiFileSystemItem sampleDataFile) {
    try {
      List<SampleDataResourceItem> fromFile = SampleDataResourceItem.getFromPsiFileSystemItem(sampleDataFile);
      if (!fromFile.isEmpty()) {
        // All items from a single file have the same namespace, look up the table cell they all go into.
        ListMultimap<String, ResourceItem> cell = myFullTable.getOrPutEmpty(fromFile.get(0).getNamespace(), ResourceType.SAMPLE_DATA);
        fromFile.forEach(item -> cell.put(item.getName(), item));
      }
    }
    catch (IOException e) {
      LOG.warn("Error loading sample data file " + sampleDataFile.getName(), e);
    }
  }

  private void addPredefinedItems() {
    ListMultimap<String, ResourceItem> cell = myFullTable.getOrPutEmpty(PREDEFINED_SAMPLES_NS, ResourceType.SAMPLE_DATA);
    PREDEFINED_SOURCES.forEach(source -> cell.put(source.getName(), source));
  }

  /**
   * Invalidates the current sample data of this repository. Call this method after the sample data has been updated to reload the contents.
   */
  void invalidate() {
    AndroidFacet facet = myAndroidFacet;
    if (facet == null || facet.isDisposed()) {
      return;
    }

    VirtualFile sampleDataDir = toVirtualFile(ProjectSystemUtil.getModuleSystem(facet.getModule()).getSampleDataDirectory());
    myFullTable.clear();

    if (sampleDataDir != null) {
      PsiManager psiManager = PsiManager.getInstance(facet.getModule().getProject());
      Stream<VirtualFile> childrenStream = Arrays.stream(sampleDataDir.getChildren());
      ApplicationManager.getApplication().runReadAction(() -> childrenStream
        .map(vf -> vf.isDirectory() ? psiManager.findDirectory(vf) : psiManager.findFile(vf))
        .filter(Objects::nonNull)
        .forEach(f -> addItems(f)));
    }

    addPredefinedItems();
    setModificationCount(ourModificationCounter.incrementAndGet());
    invalidateParentCaches(PREDEFINED_SAMPLES_NS, ResourceType.SAMPLE_DATA);
  }

  @Override
  public void addParent(@NonNull MultiResourceRepository parent) {
    AndroidFacet facet = myAndroidFacet;
    if (facet == null || facet.isDisposed()) {
      return;
    }

    super.addParent(parent);
  }

  @NonNull
  @Override
  protected ResourceTable getFullTable() {
    return myFullTable;
  }

  @Nullable
  @Override
  protected ListMultimap<String, ResourceItem> getMap(@NotNull ResourceNamespace namespace, @NonNull ResourceType type, boolean create) {
    ListMultimap<String, ResourceItem> multimap = myFullTable.get(namespace, type);
    if (multimap == null && create) {
      multimap = ArrayListMultimap.create();
      myFullTable.put(namespace, type, multimap);
    }
    return multimap;
  }

  @Override
  @NotNull
  public ResourceNamespace getNamespace() {
    return PREDEFINED_SAMPLES_NS;
  }

  @Override
  @Nullable
  public String getPackageName() {
    return PREDEFINED_SAMPLES_NS.getPackageName();
  }

  @NotNull
  @Override
  protected Set<VirtualFile> computeResourceDirs() {
    return ImmutableSet.of();
  }

  @Override
  public void dispose() {
    myAndroidFacet = null;
    super.dispose();
  }

  /**
   * Service which caches instances of {@link SampleDataResourceRepository} by their associated {@link AndroidFacet}.
   */
  static class SampleDataRepositoryManager extends AndroidFacetScopedService {
    private static final Key<SampleDataRepositoryManager> KEY = Key.create(SampleDataRepositoryManager.class.getName());
    private final Object repositoryLock = new Object();
    @GuardedBy("repositoryLock")
    private SampleDataResourceRepository repository;

    @NotNull
    public static SampleDataRepositoryManager getInstance(@NotNull AndroidFacet facet) {
      SampleDataRepositoryManager manager = facet.getUserData(KEY);

      if (manager == null) {
        manager = new SampleDataRepositoryManager(facet);
        facet.putUserData(KEY, manager);
      }

      return manager;
    }

    private SampleDataRepositoryManager(@NotNull AndroidFacet facet) {
      super(facet);
    }

    @NotNull
    public SampleDataResourceRepository getRepository() {
      if (isDisposed()) {
        throw new IllegalStateException(getClass().getName() + " is disposed");
      }
      synchronized (repositoryLock) {
        if (repository == null) {
          repository = new SampleDataResourceRepository(getFacet());

          Disposer.register(repository, () -> {
            synchronized (repositoryLock) {
              repository = null;
            }
          });
        }
        return repository;
      }
    }

    public boolean hasRepository() {
      synchronized (repositoryLock) {
        return repository != null;
      }
    }

    @Override
    public void onServiceDisposal(@NotNull AndroidFacet facet) {
      synchronized (repositoryLock) {
        repository = null;
      }
    }
  }
}
