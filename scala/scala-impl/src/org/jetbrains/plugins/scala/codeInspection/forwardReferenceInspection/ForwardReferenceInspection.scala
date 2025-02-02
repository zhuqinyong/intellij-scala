package org.jetbrains.plugins.scala.codeInspection.forwardReferenceInspection

import com.intellij.codeInspection.{LocalInspectionTool, ProblemsHolder}
import com.intellij.psi.PsiElement
import org.jetbrains.plugins.scala.ScalaBundle
import org.jetbrains.plugins.scala.codeInspection.PsiElementVisitorSimple
import org.jetbrains.plugins.scala.extensions.{PsiElementExt, PsiNamedElementExt}
import org.jetbrains.plugins.scala.lang.psi.api.expr._
import org.jetbrains.plugins.scala.lang.psi.api.statements.{ScFunction, ScValueOrVariable}
import org.jetbrains.plugins.scala.lang.psi.api.toplevel.typedef.{ScClass, ScObject}

class ForwardReferenceInspection extends LocalInspectionTool {

  import ForwardReferenceInspection._

  override def buildVisitor(holder: ProblemsHolder, isOnTheFly: Boolean): PsiElementVisitorSimple = {
    case ref: ScReferenceExpression if isDirectContextRef(ref) =>
      val maybeResolved = ref.bind()
        .map(_.getActualElement)
        .map(_.nameContext)
        .collect(asValueOrVariable)

      val isSuspicious = maybeResolved.exists(resolved =>
        ref.parents.takeWhile(propagatesControlFlowToChildren).contains(resolved.getParent) &&
          ref.getTextOffset < resolved.getTextOffset
      )

      if (isSuspicious)
        holder.registerProblem(ref, ScalaBundle.message("suspicious.forward.reference.template.body"))
    case _ =>
  }
}

object ForwardReferenceInspection {
  private def isDirectContextRef(ref: ScReferenceExpression): Boolean =
    ref.smartQualifier.forall(isThisQualifier)

  private def isThisQualifier(expr: ScExpression): Boolean = expr match {
    case _: ScThisReference => true
    case _ => false
  }

  private def propagatesControlFlowToChildren(e: PsiElement): Boolean =
    !breaksControlFlowToChildren(e)

  private def breaksControlFlowToChildren(e: PsiElement): Boolean = e match {
    case v: ScValueOrVariable if v.hasModifierProperty("lazy") => true
    case _: ScClass | _: ScObject | _: ScFunction | _: ScFunctionExpr => true
    case e: ScBlockExpr if e.hasCaseClauses => true
    case _ => false
  }

  private def asValueOrVariable: PartialFunction[PsiElement, ScValueOrVariable] = {
    case v: ScValueOrVariable if !v.hasModifierProperty("lazy") => v
  }
}
