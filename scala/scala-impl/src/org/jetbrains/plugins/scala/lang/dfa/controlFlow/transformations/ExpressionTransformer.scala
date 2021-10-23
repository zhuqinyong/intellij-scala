package org.jetbrains.plugins.scala.lang.dfa.controlFlow.transformations

import com.intellij.codeInspection.dataFlow.lang.ir.ControlFlow.DeferredOffset
import com.intellij.codeInspection.dataFlow.lang.ir._
import com.intellij.codeInspection.dataFlow.types.DfTypes
import com.intellij.psi.{PsiMethod, PsiNamedElement}
import org.jetbrains.plugins.scala.extensions._
import org.jetbrains.plugins.scala.lang.dfa.analysis.framework.ScalaStatementAnchor
import org.jetbrains.plugins.scala.lang.dfa.controlFlow.{ScalaDfaControlFlowBuilder, ScalaDfaVariableDescriptor, TransformationFailedException}
import org.jetbrains.plugins.scala.lang.dfa.utils.ScalaDfaTypeUtils.literalToDfType
import org.jetbrains.plugins.scala.lang.psi.api.base.ScLiteral
import org.jetbrains.plugins.scala.lang.psi.api.expr._

class ExpressionTransformer(val wrappedExpression: ScExpression)
  extends ScalaPsiElementTransformer(wrappedExpression) {

  override def toString: String = s"ExpressionTransformer: $wrappedExpression"

  override def transform(builder: ScalaDfaControlFlowBuilder): Unit = wrappedExpression match {
    case block: ScBlockExpr => transformBlock(block, builder)
    case parenthesised: ScParenthesisedExpr => transformParenthesisedExpression(parenthesised, builder)
    case invocation: MethodInvocation => transformInvocation(invocation, builder)
    case literal: ScLiteral => transformLiteral(literal, builder)
    case _: ScUnitExpr => transformUnitExpression(builder)
    case ifExpression: ScIf => transformIfExpression(ifExpression, builder)
    case reference: ScReferenceExpression => transformReference(reference, builder)
    case typedExpression: ScTypedExpression => transformTypedExpression(typedExpression, builder)
    case newTemplateDefinition: ScNewTemplateDefinition => transformNewTemplateDefinition(newTemplateDefinition, builder)
    case assignment: ScAssignment => transformAssignment(assignment, builder)
    case underscoreSection: ScUnderscoreSection => builder.pushUnknownCall(underscoreSection, 0)
    case functionExpression: ScFunctionExpr => builder.pushUnknownCall(functionExpression, 0)
    case _ => throw TransformationFailedException(wrappedExpression, "Unsupported expression.")
  }

  protected def transformExpression(expression: ScExpression, builder: ScalaDfaControlFlowBuilder): Unit = {
    new ExpressionTransformer(expression).transform(builder)
  }

  private def isReferenceExpressionInvocation(expression: ScReferenceExpression): Boolean = {
    expression.bind().map(_.element).exists(_.is[PsiMethod])
  }

  private def transformBlock(block: ScBlockExpr, builder: ScalaDfaControlFlowBuilder): Unit = {
    val statements = block.statements
    if (statements.isEmpty) {
      builder.pushUnknownValue()
    } else {
      statements.init.foreach { statement =>
        new ScalaPsiElementTransformer(statement).transform(builder)
        builder.popReturnValue()
      }

      transformPsiElement(statements.last, builder)
      builder.addInstruction(new FinishElementInstruction(block))
    }
  }

  private def transformParenthesisedExpression(parenthesised: ScParenthesisedExpr, builder: ScalaDfaControlFlowBuilder): Unit = {
    parenthesised.innerElement.foreach(transformExpression(_, builder))
  }

  private def transformLiteral(literal: ScLiteral, builder: ScalaDfaControlFlowBuilder): Unit = {
    builder.addInstruction(new PushValueInstruction(literalToDfType(literal), ScalaStatementAnchor(literal)))
  }

  private def transformUnitExpression(builder: ScalaDfaControlFlowBuilder): Unit = builder.pushUnknownValue()

  private def transformIfExpression(ifExpression: ScIf, builder: ScalaDfaControlFlowBuilder): Unit = {
    val skipThenOffset = new DeferredOffset
    val skipElseOffset = new DeferredOffset

    transformIfPresent(ifExpression.condition, builder)
    builder.addInstruction(new ConditionalGotoInstruction(skipThenOffset, DfTypes.FALSE, ifExpression.condition.orNull))

    builder.addInstruction(new FinishElementInstruction(null))
    transformIfPresent(ifExpression.thenExpression, builder)
    builder.addInstruction(new GotoInstruction(skipElseOffset))
    builder.setOffset(skipThenOffset)

    builder.addInstruction(new FinishElementInstruction(null))
    transformIfPresent(ifExpression.elseExpression, builder)
    builder.setOffset(skipElseOffset)

    builder.addInstruction(new FinishElementInstruction(ifExpression))
  }

  private def transformReference(expression: ScReferenceExpression, builder: ScalaDfaControlFlowBuilder): Unit = {
    if (isReferenceExpressionInvocation(expression)) {
      new InvocationTransformer(expression).transform(builder)
    } else {
      expression.qualifier.foreach { qualifier =>
        transformExpression(qualifier, builder)
        builder.popReturnValue()
      }

      ScalaDfaVariableDescriptor.fromReferenceExpression(expression) match {
        case Some(descriptor) => builder.pushVariable(descriptor, expression)
        case _ => builder.pushUnknownCall(expression, 0)
      }
    }
  }

  private def transformInvocation(invocationExpression: ScExpression, builder: ScalaDfaControlFlowBuilder): Unit = {
    new InvocationTransformer(invocationExpression).transform(builder)
  }

  private def transformTypedExpression(typedExpression: ScTypedExpression, builder: ScalaDfaControlFlowBuilder): Unit = {
    transformExpression(typedExpression.expr, builder)
  }

  private def transformNewTemplateDefinition(newTemplateDefinition: ScNewTemplateDefinition,
                                             builder: ScalaDfaControlFlowBuilder): Unit = {
    new InvocationTransformer(newTemplateDefinition).transform(builder)
  }

  private def transformAssignment(assignment: ScAssignment, builder: ScalaDfaControlFlowBuilder): Unit = {
    assignment.leftExpression match {
      case reference: ScReferenceExpression => reference.bind().map(_.element) match {
        case Some(element: PsiNamedElement) =>
          val descriptor = ScalaDfaVariableDescriptor(element, isStable = false)
          builder.assignVariableValue(descriptor, assignment.rightExpression)
        case _ => builder.pushUnknownCall(assignment, 0)
      }
      case _ => builder.pushUnknownCall(assignment, 0)
    }
  }
}
