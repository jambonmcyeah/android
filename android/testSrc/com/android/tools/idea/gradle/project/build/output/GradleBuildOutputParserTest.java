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
package com.android.tools.idea.gradle.project.build.output;

import com.intellij.build.events.BuildEvent;
import com.intellij.build.events.FileMessageEvent;
import com.intellij.build.events.MessageEvent;
import com.intellij.build.output.BuildOutputInstantReader;
import com.intellij.util.SystemProperties;
import org.jetbrains.annotations.Nullable;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;

import java.util.List;
import java.util.function.Consumer;

import static com.android.tools.idea.gradle.project.build.output.GradleBuildOutputParser.END_DETAIL;
import static com.google.common.truth.Truth.assertThat;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.MockitoAnnotations.initMocks;

public class GradleBuildOutputParserTest {
  @Mock private BuildOutputInstantReader myReader;
  @Mock private Consumer<BuildEvent> myConsumer;
  @Nullable private GradleBuildOutputParser myParser;

  @Before
  public void setUp() {
    initMocks(this);
    myParser = new GradleBuildOutputParser();
  }

  @Test
  public void parseWithError() {
    String line = "AGPBI: {\"kind\":\"error\",\"text\":\"Error message.\",\"sources\":[{\"file\":\"/app/src/main/res/layout/activity_main.xml\",\"position\":{\"startLine\":10,\"startColumn\":31,\"startOffset\":456,\"endColumn\":44,\"endOffset\":469}}],\"original\":\"\",\"tool\":\"AAPT\"}";
    when(myReader.getParentEventId()).thenReturn("BUILD_ID_MOCK");

    ArgumentCaptor<MessageEvent> messageCaptor = ArgumentCaptor.forClass(MessageEvent.class);
    String detailLine = "This is a detail line";
    assertFalse(myParser.parse(line, myReader, myConsumer));
    assertTrue(myParser.processingMessage());
    assertFalse(myParser.parse(detailLine, myReader, myConsumer));
    assertTrue(myParser.processingMessage());
    assertTrue(myParser.parse(END_DETAIL, myReader, myConsumer));
    assertFalse(myParser.processingMessage());
    verify(myConsumer).accept(messageCaptor.capture());

    List<MessageEvent> generatedMessages = messageCaptor.getAllValues();
    assertThat(generatedMessages).hasSize(1);
    assertThat(generatedMessages.get(0)).isInstanceOf(FileMessageEvent.class);
    FileMessageEvent fileMessageEvent = (FileMessageEvent)generatedMessages.get(0);
    String detailMessage = line + SystemProperties.getLineSeparator() + detailLine;
    assertThat(fileMessageEvent.getResult().getDetails()).isEqualTo(detailMessage);
  }

  @Test
  public void parseWithErrorNoSource() {
    String line = "AGPBI: {\"kind\":\"error\",\"text\":\"Warning message.\",\"sources\":[{}],\"original\":\"\",\"tool\":\"AAPT\"}";
    when(myReader.getParentEventId()).thenReturn("BUILD_ID_MOCK");

    ArgumentCaptor<MessageEvent> messageCaptor = ArgumentCaptor.forClass(MessageEvent.class);
    String detailLine = "This is a detail line";
    assertFalse(myParser.parse(line, myReader, myConsumer));
    assertTrue(myParser.processingMessage());
    assertFalse(myParser.parse(detailLine, myReader, myConsumer));
    assertTrue(myParser.processingMessage());
    assertTrue(myParser.parse(END_DETAIL, myReader, myConsumer));
    assertFalse(myParser.processingMessage());
    verify(myConsumer).accept(messageCaptor.capture());

    List<MessageEvent> generatedMessages = messageCaptor.getAllValues();
    assertThat(generatedMessages).hasSize(1);
    assertThat(generatedMessages.get(0)).isNotInstanceOf(FileMessageEvent.class);
    String detailMessage = line + SystemProperties.getLineSeparator() + detailLine;
    MessageEvent messageEvent = generatedMessages.get(0);
    assertThat(messageEvent.getResult().getDetails()).isEqualTo(detailMessage);
  }

  @Test
  public void parseWithoutError() {
    String line = "Non AGBPI error";
    assertFalse(myParser.parse(line, myReader, myConsumer));
    assertFalse(myParser.processingMessage());
  }

  @Test
  public void parseChangeBuildId() {
    String startline = "AGPBI: {\"kind\":\"error\",\"text\":\"Warning message.\",\"sources\":[{}],\"original\":\"\",\"tool\":\"AAPT\"}";
    String noErrorLine = "This is not an error";

    when(myReader.getParentEventId()).thenReturn("BUILD_ID_MOCK_1");
    assertFalse(myParser.parse(startline, myReader, myConsumer));
    assertTrue(myParser.processingMessage());

    when(myReader.getParentEventId()).thenReturn("BUILD_ID_MOCK_2");
    assertFalse(myParser.parse(startline, myReader, myConsumer));
    assertTrue(myParser.processingMessage());

    when(myReader.getParentEventId()).thenReturn("BUILD_ID_MOCK_3");
    assertFalse(myParser.parse(noErrorLine, myReader, myConsumer));
    assertFalse(myParser.processingMessage());
  }
}