package org.jetbrains.plugins.scala
package codeInspection.syntacticClarification

import com.intellij.codeInspection.ProblemsHolder
import com.intellij.openapi.project.Project
import com.intellij.psi.PsiElement
import org.jetbrains.plugins.scala.codeInspection.{AbstractFixOnPsiElement, AbstractInspection, InspectionBundle}
import org.jetbrains.plugins.scala.lang.psi.api.expr.{MethodInvocation, ScMethodCall, ScReferenceExpression}
import org.jetbrains.plugins.scala.lang.psi.impl.ScalaPsiElementFactory.createExpressionFromText

/**
 * Nikolay.Tropin
 * 2014-09-26
 */
class AutoTuplingInspection extends AbstractInspection(InspectionBundle.message("display.name.auto.tupling")) {

  override def actionFor(implicit holder: ProblemsHolder, isOnTheFly: Boolean): PartialFunction[PsiElement, Any] = {
    case mc @ ScMethodCall(ref: ScReferenceExpression, _) if ref.bind().exists(_.tuplingUsed) =>
      holder.registerProblem(mc.args, InspectionBundle.message("scala.compiler.will.replace.this.argument.list.with.tuple"), new MakeTuplesExplicitFix(mc))
  }
}

class MakeTuplesExplicitFix(invoc: MethodInvocation) extends AbstractFixOnPsiElement(InspectionBundle.message("make.tuple.explicit"), invoc) {

  override protected def doApplyFix(element: MethodInvocation)
                                   (implicit project: Project): Unit = element match {
    case mc: ScMethodCall =>
      val newArgsText = s"(${mc.args.getText})"
      val invokedExprText = mc.getInvokedExpr.getText
      val newCall = createExpressionFromText(s"$invokedExprText$newArgsText")
      mc.replaceExpression(newCall, removeParenthesis = false)
    case _ =>

  }
}
