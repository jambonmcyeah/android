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
package com.android.tools.perf.idea.gradle.project.sync;

import static com.android.tools.idea.testing.TestProjectPaths.SYNC_PERF_AVERAGE_PROJECT;

import org.jetbrains.annotations.NotNull;

/**
 * Measure performance for IDEA sync using the average project.
 */
public class IDEASyncAveragePerfTest extends GradleSyncPerformanceTestCase {
  @NotNull
  @Override
  public String getMetricName() {
    return "Average Project IDEA sync";
  }

  @NotNull
  @Override
  public String getRelativePath() {
    return SYNC_PERF_AVERAGE_PROJECT;
  }

  @Override
  protected boolean useNewSyncInfrastructure() {
    return false;
  }

  @Override
  protected boolean useSingleVariantSyncInfrastructure() {
    return false;
  }

  @Override
  protected boolean useCompoundSyncInfrastructure() {
    return false;
  }
}
