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
package com.android.tools.idea.gradle.structure.configurables.suggestions

import com.android.tools.idea.gradle.structure.configurables.PsContext
import com.android.tools.idea.gradle.structure.configurables.issues.IssueRenderer
import com.android.tools.idea.gradle.structure.model.PsIssue
import com.android.tools.idea.gradle.structure.model.PsPath
import com.android.tools.idea.gradle.structure.model.parents

class SuggestionsViewIssueRenderer(val context: PsContext) : IssueRenderer {

  override fun renderIssue(buffer: StringBuilder, issue: PsIssue, scope: PsPath?) {
    val issuePath = issue.path
    val issuePathHref = issuePath.getHyperlinkDestination(context)
    val issuePathText = issuePath.toString().makeTextWrappable()
    val issueText = issue.text.makeTextWrappable()

    buffer.append("<table width='100%'><tr><td width='32' valign='top'>")
    buffer.append("<img width=16 height=16 src='${issue.severity.icon}'></img>")
    buffer.append("</td><td valign='top'>")
    buffer.append("<b>")
    buffer.append(issuePathText)
    issuePath.parents.asReversed().takeWhile { it != scope }.asReversed().forEach { parentPath ->
      val parentPathHref = parentPath.getHyperlinkDestination(context)
      val parentPathText = parentPath.toString().makeTextWrappable()
      buffer.append(" (<a href=\"$parentPathHref\">$parentPathText</a>)")
    }
    buffer.append(" : ")
    buffer.append(issueText)
    buffer.append("</b>")
    if (issuePathHref != null) {
      buffer.append("<br/><a href='$issuePathHref'>View usage</a>")
    }
    buffer.append("</td></tr></table>")
  }
}