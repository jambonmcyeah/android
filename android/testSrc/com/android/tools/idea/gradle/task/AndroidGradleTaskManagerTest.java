// Copyright 2000-2019 JetBrains s.r.o. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.android.tools.idea.gradle.task;

import com.android.tools.idea.gradle.project.GradleProjectInfo;
import com.android.tools.idea.gradle.project.build.compiler.AndroidGradleBuildConfiguration;
import com.android.tools.idea.gradle.project.build.invoker.GradleBuildInvoker;
import com.android.tools.idea.gradle.project.facet.gradle.GradleFacet;
import com.android.tools.idea.gradle.util.GradleUtil;
import com.intellij.facet.FacetManager;
import com.intellij.openapi.externalSystem.ExternalSystemModulePropertyManager;
import com.intellij.openapi.externalSystem.model.project.ModuleData;
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskId;
import com.intellij.openapi.externalSystem.model.task.ExternalSystemTaskNotificationListenerAdapter;
import com.intellij.openapi.module.Module;
import com.intellij.openapi.module.ModuleManager;
import com.intellij.openapi.project.Project;
import org.jetbrains.annotations.NotNull;
import org.junit.Test;
import org.mockito.ArgumentMatcher;
import org.picocontainer.PicoContainer;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.mockito.Mockito.*;

/**
 * @author Vladislav.Soroka
 */
public class AndroidGradleTaskManagerTest {
  @Test
  public void executeTasks() {
    String projectPath = "projectPath";
    List<String> taskNames = Arrays.asList("fooTask");
    ExternalSystemTaskId taskId = mock(ExternalSystemTaskId.class);
    Project project = mock(Project.class);
    GradleBuildInvoker gradleBuildInvoker = createInvokerMock(projectPath, taskId, project);
    ExternalSystemTaskNotificationListenerAdapter listener = new ExternalSystemTaskNotificationListenerAdapter() {};

    new AndroidGradleTaskManager().executeTasks(taskId, taskNames, projectPath, null, null, listener);

    verify(gradleBuildInvoker).executeTasks(argThat(new RequestMatcher(
      new GradleBuildInvoker.Request(project, new File(projectPath), taskNames, taskId)
        .setJvmArguments(new ArrayList<>())
        .setCommandLineArguments(new ArrayList<>())
        .setTaskListener(listener)
        .waitForCompletion())));
  }

  @NotNull
  private static GradleBuildInvoker createInvokerMock(String projectPath, ExternalSystemTaskId taskId, Project project) {
    GradleBuildInvoker gradleBuildInvoker = mock(GradleBuildInvoker.class);
    PicoContainer picoContainer = mock(PicoContainer.class);
    GradleProjectInfo gradleProjectInfo = mock(GradleProjectInfo.class);
    when(taskId.findProject()).thenReturn(project);
    when(project.getPicoContainer()).thenReturn(picoContainer);
    when(gradleProjectInfo.isDirectGradleBuildEnabled()).thenReturn(true);
    AndroidGradleBuildConfiguration androidGradleBuildConfiguration = new AndroidGradleBuildConfiguration();
    androidGradleBuildConfiguration.USE_EXPERIMENTAL_FASTER_BUILD = true;
    when(picoContainer.getComponentInstance(AndroidGradleBuildConfiguration.class.getName())).thenReturn(androidGradleBuildConfiguration);
    when(gradleBuildInvoker.getProject()).thenReturn(project);
    ModuleManager moduleManager = mock(ModuleManager.class);

    when(project.getComponent(ModuleManager.class)).thenReturn(moduleManager);
    when(project.getService(GradleProjectInfo.class)).thenReturn(gradleProjectInfo);
    when(project.getService(GradleBuildInvoker.class)).thenReturn(gradleBuildInvoker);

    Module module = mock(Module.class);
    when(moduleManager.getModules()).thenReturn(new Module[]{module});
    when(module.getPicoContainer()).thenReturn(picoContainer);
    when(module.getProject()).thenReturn(project);
    FacetManager facetManager = mock(FacetManager.class);
    when(facetManager.getFacetByType(GradleFacet.getFacetTypeId())).thenReturn(mock(GradleFacet.class));
    when(module.getComponent(FacetManager.class)).thenReturn(facetManager);
    ExternalSystemModulePropertyManager modulePropertyManager = new ExternalSystemModulePropertyManager(module);
    when(picoContainer.getComponentInstance(ExternalSystemModulePropertyManager.class.getName())).thenReturn(modulePropertyManager);
    modulePropertyManager.setExternalOptions(GradleUtil.GRADLE_SYSTEM_ID, new ModuleData("", GradleUtil.GRADLE_SYSTEM_ID,
                                                                                         "", "", "", projectPath), null);
    when(module.getOptionValue("external.linked.project.path")).thenReturn(projectPath);
    return gradleBuildInvoker;
  }

  private static class RequestMatcher implements ArgumentMatcher<GradleBuildInvoker.Request> {
    private final GradleBuildInvoker.Request myRequest;

    RequestMatcher(GradleBuildInvoker.Request request) {
      myRequest = request;
    }

    @Override
    public boolean matches(GradleBuildInvoker.Request argument) {
      // skip generated init scripts args asserting
      argument.setCommandLineArguments(Collections.emptyList());
      return myRequest.toString().equals(argument.toString()) && myRequest.getTaskListener() == argument.getTaskListener();
    }
  }
}