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

// ATTENTION: This file has been automatically generated from db.bnf. Do not edit it manually.

package com.android.tools.idea.lang.databinding.psi.impl;

import org.jetbrains.annotations.*;
import com.intellij.lang.ASTNode;
import com.intellij.psi.PsiElementVisitor;
import com.android.tools.idea.lang.databinding.psi.*;

public class PsiDbRefExprImpl extends PsiDbExprImpl implements PsiDbRefExpr {

  public PsiDbRefExprImpl(ASTNode node) {
    super(node);
  }

  public void accept(@NotNull PsiDbVisitor visitor) {
    visitor.visitRefExpr(this);
  }

  public void accept(@NotNull PsiElementVisitor visitor) {
    if (visitor instanceof PsiDbVisitor) accept((PsiDbVisitor)visitor);
    else super.accept(visitor);
  }

  @Override
  @Nullable
  public PsiDbExpr getExpr() {
    return findChildByClass(PsiDbExpr.class);
  }

  @Override
  @NotNull
  public PsiDbId getId() {
    return findNotNullChildByClass(PsiDbId.class);
  }

}
