package org.jetbrains.plugins.scala
package lang.psi.api

import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.psi._
import com.intellij.psi.impl.migration.PsiMigrationManager
import com.intellij.psi.scope.{NameHint, PsiScopeProcessor}
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.plugins.scala.extensions.{StubBasedExt, _}
import org.jetbrains.plugins.scala.externalLibraries.bm4.BetterMonadicForSupport
import org.jetbrains.plugins.scala.externalLibraries.kindProjector.KindProjectorUtil
import org.jetbrains.plugins.scala.lang.psi.api.ScPackageLike.processPackageObject
import org.jetbrains.plugins.scala.lang.psi.api.base.ScReference
import org.jetbrains.plugins.scala.lang.psi.api.toplevel.ScPackaging
import org.jetbrains.plugins.scala.lang.psi.api.toplevel.imports.ScImportStmt
import org.jetbrains.plugins.scala.lang.psi.api.toplevel.typedef.ScObject
import org.jetbrains.plugins.scala.lang.psi.impl._
import org.jetbrains.plugins.scala.lang.psi.{ScDeclarationSequenceHolder, ScExportsHolder, ScImportsHolder}
import org.jetbrains.plugins.scala.lang.resolve.ScalaResolveState.ResolveStateExt
import org.jetbrains.plugins.scala.lang.resolve.processor.precedence.{PrecedenceTypes, SubstitutablePrecedenceHelper}
import org.jetbrains.plugins.scala.lang.resolve.processor.{BaseProcessor, ResolveProcessor}
import org.jetbrains.plugins.scala.project.{ModuleExt, ProjectPsiElementExt, ScalaLanguageLevel}
import org.jetbrains.plugins.scala.settings.ScalaProjectSettings
import org.jetbrains.plugins.scala.settings.ScalaProjectSettings.AliasExportSemantics

/**
  * User: Dmitry Naydanov
  * Date: 12/12/12
  */
trait FileDeclarationsHolder
  extends ScDeclarationSequenceHolder
    with ScImportsHolder
    with ScExportsHolder {

  import FileDeclarationsHolder._
  import ScPackageImpl._

  override def processDeclarations(processor: PsiScopeProcessor,
                                   state: ResolveState,
                                   lastParent: PsiElement,
                                   place: PsiElement): Boolean = {
    if (isProcessLocalClasses(lastParent) &&
      !super[ScDeclarationSequenceHolder].processDeclarations(processor, state, lastParent, place)) return false

    if (!processDeclarationsFromImports(processor, state, lastParent, place)) return false

    if (this.context != null) return true

    if (place.kindProjectorEnabled) {
      KindProjectorUtil(place.getProject)
        .syntheticDeclarations(place)
        .foreach(processor.execute(_, state))
    }

    if (place.betterMonadicForEnabled) {
      BetterMonadicForSupport(place.getProject)
        .syntheticDeclarations
        .foreach(processor.execute(_, state))
    }

    implicit val scope: GlobalSearchScope = place.resolveScope
    implicit val manager: ScalaPsiManager = ScalaPsiManager.instance(getProject)

    val defaultPackage = ScPackageImpl.findPackage("")
    place match {
      case ref: ScReference if ref.refName == "_root_" && ref.qualifier.isEmpty =>
        if (defaultPackage != null && !processor.execute(defaultPackage, state.withRename("_root_"))) return false
      case _ =>
        if (place != null && PsiTreeUtil.getParentOfType(place, classOf[ScPackaging]) == null) {
          if (defaultPackage != null &&
            !packageProcessDeclarations(defaultPackage)(processor, state, null, place)) return false
          if (defaultPackage != null &&
            this.isInScala3Module &&
            !defaultPackage.processTopLevelDeclarations(processor, state, place)) return false

        }
        else if (defaultPackage != null && !BaseProcessor.isImplicitProcessor(processor)) {
          //we will add only packages
          //only packages resolve, no classes from default package
          val name = processor.getHint(NameHint.KEY) match {
            case null => null
            case hint => hint.getName(state)
          }
          if (name == null) {
            val packages = defaultPackage.getSubPackages(scope)
            val iterator = packages.iterator
            while (iterator.hasNext) {
              val pack = iterator.next()
              if (!processor.execute(pack, state)) return false
            }
            val migration = PsiMigrationManager.getInstance(getProject).getCurrentMigration
            if (migration != null) {
              val list = migration.getMigrationPackages("")
              val packages = list.toArray(new Array[PsiPackage](list.size)).map(ScPackageImpl(_))
              val iterator = packages.iterator
              while (iterator.hasNext) {
                val pack = iterator.next()
                if (!processor.execute(pack, state)) return false
              }
            }
          } else {
            manager.getCachedPackageInScope(name)
              .map(ScPackageImpl(_))
              .foreach { `package` =>
                if (!processor.execute(`package`, state)) return false
              }
          }
        }
    }

    FileDeclarationsContributor.getAllFor(this).foreach(
      _.processAdditionalDeclarations(processor, this, state)
    )


    val checkPredefinedClassesAndPackages = processor match {
      case r: ResolveProcessor => r.checkPredefinedClassesAndPackages()
      case _                   => true
    }

    if (checkPredefinedClassesAndPackages) {
      if (ScalaProjectSettings.in(getProject).aliasExportsEnabled && lastParent.defaultImports.exists(s => s == "scala" || s == "scala.Predef")) {
        if (aliasImports.exists(!_.processDeclarations(processor, state, lastParent, place))) return false;
      }

      if (!processImplicitImports(processor, state, place)) return false
    }

    true
  }

  private lazy val aliasImports: Seq[ScImportStmt] = {
    val file = ScalaPsiElementFactory.createScalaFileFromText(aliasImportsFor(this.scalaLanguageLevelOrDefault))
    file.context = this
    file.children.filterByType[ScImportStmt].toSeq.reverse
  }

  def processImplicitImports(processor: PsiScopeProcessor,
                             state: ResolveState,
                             place: PsiElement)
                            (implicit manager: ScalaPsiManager,
                             scope: GlobalSearchScope)
  : Boolean = {
    val precedenceTypes = PrecedenceTypes.forElement(this)
    val importedFqns = precedenceTypes.defaultImportsWithPrecedence

    importedFqns.foreach { case (fqn, precedence) =>
      ProgressManager.checkCanceled()
      if (!shouldNotProcessDefaultImport(fqn)) {

        updateProcessor(processor, precedence) {
          manager.getCachedClasses(scope, fqn)
            .findByType[ScObject]
            .foreach { `object` =>
              if (!processPackageObject(`object`)(processor, state, null, place))
                return false
            }

          manager.getCachedPackage(fqn)
            .foreach { `package` =>
              if (!packageProcessDeclarations(`package`)(processor, state, null, place))
                return false
            }
        }
      }

      /* scala package requires special treatment to process synthetic classes/objects */
      if (fqn == ScalaLowerCase &&
        !processScalaPackage(processor, state))
        return false
    }

    true
  }

  protected def shouldNotProcessDefaultImport(fqn: String): Boolean
}

//noinspection TypeAnnotation
object FileDeclarationsHolder {

  // https://youtrack.jetbrains.com/issue/SCL-19928
  // Precomputed "implicit imports" that match "alias exports" in scala and scala.Predef
  // E.g. Seq is scala.collection.immutable.Seq rather than scala.Seq

  // TODO Resolve through "alias exports" dynamically

  private val AliasImports212 = """
     |import _root_.java.lang.{Throwable, Exception, Error, RuntimeException, NullPointerException, ClassCastException, IndexOutOfBoundsException, ArrayIndexOutOfBoundsException, StringIndexOutOfBoundsException, UnsupportedOperationException, IllegalArgumentException, NumberFormatException, AbstractMethodError, InterruptedException, String, Class}
     |import _root_.java.util.NoSuchElementException
     |import _root_.scala.collection.{Iterable, Seq, IndexedSeq, Iterator, BufferedIterator, Iterable, +:, :+}
     |import _root_.scala.collection.immutable.{List, Nil, ::, Stream, Vector, Range, Map, Set}
     |import _root_.scala.collection.immutable.Stream.#::
     |import _root_.scala.collection.mutable.StringBuilder
     |import _root_.scala.math.{BigDecimal, BigInt, Equiv, Fractional, Integral, Numeric, Ordered, Ordering, PartialOrdering, PartiallyOrdered}
     |import _root_.scala.util.{Either, Left, Right}
     |import _root_.scala.reflect.{OptManifest, Manifest, NoManifest}
   """.stripMargin.trim

  private val AliasImports213 = """
     |import _root_.java.lang.{Cloneable, Throwable, Exception, Error, RuntimeException, NullPointerException, ClassCastException, IndexOutOfBoundsException, ArrayIndexOutOfBoundsException, StringIndexOutOfBoundsException, UnsupportedOperationException, IllegalArgumentException, NumberFormatException, AbstractMethodError, InterruptedException, String, Class}
     |import _root_.java.io.Serializable
     |import _root_.java.util.NoSuchElementException
     |import _root_.scala.collection.{IterableOnce, Iterable, Iterator, +:, :+}
     |import _root_.scala.collection.immutable.{Seq, IndexedSeq, List, Nil, ::, Stream, LazyList, Vector, Range, Map, Set}
     |import _root_.scala.collection.mutable.StringBuilder
     |import _root_.scala.math.{BigDecimal, BigInt, Equiv, Fractional, Integral, Numeric, Ordered, Ordering, PartialOrdering, PartiallyOrdered}
     |import _root_.scala.util.{Either, Left, Right}
     |import _root_.scala.reflect.{OptManifest, Manifest, NoManifest}
   """.stripMargin.trim

  private def aliasImportsFor(level: ScalaLanguageLevel): String =
    if (level < ScalaLanguageLevel.Scala_2_13) AliasImports212 else AliasImports213

  def isAliasImport(fqn: String, level: ScalaLanguageLevel): Boolean =
    (if (level < ScalaLanguageLevel.Scala_2_13) aliasImports212 else aliasImports213)(fqn)

  private lazy val aliasImports212: Set[String] = AliasImports212.linesIterator.flatMap(importsIn).toSet

  private lazy val aliasImports213: Set[String] = AliasImports213.linesIterator.flatMap(importsIn).toSet

  private def importsIn(line: String): Seq[String] = {
    val s = line.substring(14) // import _root_.
    val i = s.indexOf('{')
    if (i == -1) Seq(s) else {
      val (prefix, suffix) = (s.substring(0, i), s.substring(i + 1, s.length - 1))
      suffix.split(",\\s*").toIndexedSeq.map(prefix + _)
    }
  }

  //method extracted due to VerifyError in Scala compiler
  private def updateProcessor(processor: PsiScopeProcessor, priority: Int)
                             (body: => Unit): Unit =
    processor match {
      case b: BaseProcessor with SubstitutablePrecedenceHelper => b.runWithPriority(priority)(body)
      case _                                                   => body
    }

  /**
    * @param _place actual place, can be null, if null => false
    * @return true, if place is out of source content root, or in Scala Worksheet.
    */
  def isProcessLocalClasses(_place: PsiElement): Boolean = {
    val place = _place match {
      case s: ScalaPsiElement => s.getDeepSameElementInContext
      case _ => _place
    }
    if (place == null || place.hasOnlyStub) return false

    place.getContainingFile match {
      case scalaFile: ScalaFile if scalaFile.isWorksheetFile => true
      case scalaFile: ScalaFile =>
        val file = Option(scalaFile.getOriginalFile.getVirtualFile).getOrElse(scalaFile.getViewProvider.getVirtualFile)
        if (file == null) return false

        val index = ProjectRootManager.getInstance(place.getProject).getFileIndex
        val belongsToProject =
          index.isInSourceContent(file) || index.isInLibraryClasses(file)
        !belongsToProject
      case _ => false
    }
  }

}
