package org.jetbrains.plugins.scala
package lang
package psi
package impl
package toplevel
package packaging

import com.intellij.lang.ASTNode
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.project.DumbService
import com.intellij.psi._
import com.intellij.psi.scope.PsiScopeProcessor
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.plugins.scala.caches.ScalaShortNamesCacheManager
import org.jetbrains.plugins.scala.extensions.PsiElementExt
import org.jetbrains.plugins.scala.lang.TokenSets.TYPE_DEFINITIONS
import org.jetbrains.plugins.scala.lang.lexer.ScalaTokenTypes
import org.jetbrains.plugins.scala.lang.parser.ScalaElementType
import org.jetbrains.plugins.scala.lang.psi.api.base.ScStableCodeReferenceElement
import org.jetbrains.plugins.scala.lang.psi.api.toplevel.ScPackaging
import org.jetbrains.plugins.scala.lang.psi.api.toplevel.typedef.{ScObject, ScTypeDefinition}
import org.jetbrains.plugins.scala.lang.psi.api.{ScPackageLike, ScalaFile}
import org.jetbrains.plugins.scala.lang.psi.stubs.ScPackagingStub
import org.jetbrains.plugins.scala.lang.psi.stubs.elements.ScStubElementType
import org.jetbrains.plugins.scala.lang.resolve.processor.BaseProcessor

/**
  * @author Alexander Podkhalyuzin, Pavel Fatin
  *         Date: 20.02.2008
  */
final class ScPackagingImpl private[psi](stub: ScPackagingStub,
                                         nodeType: ScStubElementType[ScPackagingStub, ScPackaging],
                                         node: ASTNode)
  extends ScalaStubBasedElementImpl(stub, nodeType, node)
    with ScPackaging
    with ScImportsHolder // todo: to be removed
    with ScDeclarationSequenceHolder {

  override def toString = "ScPackaging"

  def reference: Option[ScStableCodeReferenceElement] =
    Option(getFirstChild).flatMap { node =>
      Option(node.getNextSibling)
    }.flatMap { node =>
      Option(node.getNextSibling)
    }.collect {
      case reference: ScStableCodeReferenceElement => reference
    }.orElse {
      findChild(classOf[ScStableCodeReferenceElement])
    }

  override def packagings: Seq[ScPackaging] =
    getStubOrPsiChildren(ScalaElementType.PACKAGING, JavaArrayFactoryUtil.ScPackagingFactory)

  override def isExplicit: Boolean = byStubOrPsi(_.isExplicit)(findLeftBrace.isDefined)

  override def packageName: String = byStubOrPsi(_.packageName)(reference.fold("")(_.qualName))

  override def parentPackageName: String = byStubOrPsi(_.parentPackageName)(ScPackagingImpl.parentPackageName(this))

  override def fullPackageName: String = ScPackagingImpl.fullPackageName(parentPackageName, packageName)

  def declaredElements: Seq[ScPackageImpl] = {
    val name = packageName
    val topRefName = name.indexOf(".") match {
      case -1 => name
      case index => name.substring(0, index)
    }

    val top = ScPackagingImpl.fullPackageName(parentPackageName, topRefName)
    findPackage(top).toSeq
  }

  override def processDeclarations(processor: PsiScopeProcessor,
                                   state: ResolveState,
                                   lastParent: PsiElement,
                                   place: PsiElement): Boolean = {
    if (DumbService.getInstance(getProject).isDumb) return true

    //If stub is not null, then we are not trying to resolve packaging reference.
    if (getStub != null || !reference.contains(lastParent)) {
      ProgressManager.checkCanceled()

      findPackage(fullPackageName) match {
        case Some(p) if !p.processDeclarations(processor, state, lastParent, place) => return false
        case _ =>
      }

      findPackageObject(place.resolveScope).foreach { definition =>
        var newState = state
        definition.`type`().foreach { tp =>
          newState = state.put(BaseProcessor.FROM_TYPE_KEY, tp)
        }
        if (!definition.processDeclarations(processor, newState, lastParent, place)) return false
      }
    }

    if (lastParent != null && lastParent.getContext == this) {
      if (!super[ScImportsHolder].processDeclarations(processor,
        state, lastParent, place)) return false

      if (ScalaFileImpl.isProcessLocalClasses(lastParent) &&
        !super[ScDeclarationSequenceHolder].processDeclarations(processor, state, lastParent, place)) return false
    }

    true
  }

  def findPackageObject(scope: GlobalSearchScope): Option[ScObject] =
    ScalaShortNamesCacheManager.getInstance(getProject)
      .findPackageObjectByName(fullPackageName, scope)

  def bodyText: String = {
    val text = getText
    val endOffset = text.length

    findLeftBrace match {
      case Some(brace) =>
        val startOffset = brace.getTextRange.getEndOffset - getTextRange.getStartOffset

        val length = if (text(text.length - 1) == '}') 1 else 0
        text.substring(startOffset, endOffset - length)
      case _ =>
        var ref = findChildByType[PsiElement](ScalaElementType.REFERENCE)
        if (ref == null) ref = findChildByType[PsiElement](ScalaTokenTypes.kPACKAGE)
        if (ref == null) return text

        val startOffset = ref.getTextRange.getEndOffset + 1 - getTextRange.getStartOffset
        if (startOffset >= endOffset) "" else text.substring(startOffset, endOffset)
    }
  }

  override protected def childBeforeFirstImport: Option[PsiElement] =
    findLeftBrace.orElse(reference)

  override def parentScalaPackage: Option[ScPackageLike] = {
    Option(PsiTreeUtil.getContextOfType(this, true, classOf[ScPackageLike])).orElse {
      ScalaPsiUtil.parentPackage(fullPackageName, getProject)
    }
  }

  override def immediateTypeDefinitions: Seq[ScTypeDefinition] = getStubOrPsiChildren(TYPE_DEFINITIONS, JavaArrayFactoryUtil.ScTypeDefinitionFactory)

  private def findLeftBrace = Option(findChildByType[PsiElement](ScalaTokenTypes.tLBRACE))

  private def findPackage(name: String) =
    Option(JavaPsiFacade.getInstance(getProject).findPackage(name))
      .map(ScPackageImpl(_))
}

object ScPackagingImpl {

  private def fullPackageName(parentPackageName: String, packageName: String): String = {
    val infix = parentPackageName match {
      case "" => ""
      case _ => "."
    }
    s"$parentPackageName$infix$packageName"
  }

  private def parentPackageName(element: PsiElement): String = element.getParent match {
    case packaging: ScPackaging =>
      fullPackageName(parentPackageName(packaging), packaging.packageName)
    case _: ScalaFile |
         null => ""
    case parent => parentPackageName(parent)
  }
}

