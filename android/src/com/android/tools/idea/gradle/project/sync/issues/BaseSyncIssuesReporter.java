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
package com.android.tools.idea.gradle.project.sync.issues;

import com.android.builder.model.SyncIssue;
import com.android.tools.idea.gradle.project.sync.messages.GradleSyncMessages;
import com.android.tools.idea.project.messages.MessageType;
import com.android.tools.idea.project.messages.SyncMessage;
import com.android.tools.idea.util.PositionInFile;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.vfs.VirtualFile;
import org.intellij.lang.annotations.MagicConstant;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.Map;

import static com.android.builder.model.SyncIssue.SEVERITY_ERROR;
import static com.android.tools.idea.project.messages.MessageType.*;
import static com.android.tools.idea.project.messages.SyncMessage.DEFAULT_GROUP;

/**
 * Base class for issue reporters, defines base behaviour for classes to report different types of notifications that are obtained from
 * syncing a Gradle project with Android Studio.
 */
abstract class BaseSyncIssuesReporter {
  @NotNull
  GradleSyncMessages getSyncMessages(@NotNull Module module) {
    return GradleSyncMessages.getInstance(module.getProject());
  }

  /**
   * @return the type of messages this reporter should be run against. This reporter will only be invoked for sync issues of this type.
   */
  @MagicConstant(valuesFromClass = SyncIssue.class)
  abstract int getSupportedIssueType();

  /**
   * Reports a single sync issue from a given module with an optional build file. In most cases use of this method should be avoided
   * in favor of {@link #reportAll(List, Map, Map)} which gives more context for reporters to provide enhanced features (e.g deduplication
   * of notifications across modules).
   */
  abstract void report(@NotNull SyncIssue syncIssue, @NotNull Module module, @Nullable VirtualFile buildFile);

  /**
   * @param syncIssues   list of sync issues to be reported.
   * @param moduleMap    provides the origin module of each sync issue, this map MUST contain every sync issue provided in syncIssues.
   * @param buildFileMap map of build files per module, this map provides information to each of the reporters to support quick links to
   *                     the build.gradle files, entries in this map are optional.
   */
  void reportAll(@NotNull List<SyncIssue> syncIssues,
                 @NotNull Map<SyncIssue, Module> moduleMap,
                 @NotNull Map<Module, VirtualFile> buildFileMap) {
    // Fall back to individual reporting.
    for (SyncIssue issue : syncIssues) {
      report(issue, moduleMap.get(issue), buildFileMap.get(moduleMap.get(issue)));
    }
  }

  @NotNull
  static MessageType getMessageType(@NotNull SyncIssue syncIssue) {
    return syncIssue.getSeverity() == SEVERITY_ERROR ? ERROR : WARNING;
  }

  @NotNull
  static SyncMessage generateSyncMessage(@NotNull SyncIssue syncIssue, @NotNull Module module, @Nullable VirtualFile buildFile) {
    SyncMessage message;
    if (buildFile != null) {
      PositionInFile position = new PositionInFile(buildFile);
      message = new SyncMessage(module.getProject(), DEFAULT_GROUP, findFromSyncIssue(syncIssue), position, syncIssue.getMessage());
    }
    else {
      message = new SyncMessage(DEFAULT_GROUP, findFromSyncIssue(syncIssue), syncIssue.getMessage());
    }
    return message;
  }
}
