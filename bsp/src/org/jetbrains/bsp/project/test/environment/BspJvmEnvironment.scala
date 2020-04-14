package org.jetbrains.bsp.project.test.environment

import java.net.URI
import java.nio.file.Paths
import java.util.concurrent.CompletableFuture

import ch.epfl.scala.bsp4j.BuildServerCapabilities
import ch.epfl.scala.bsp4j.BuildTargetIdentifier
import ch.epfl.scala.bsp4j.JvmEnvironmentItem
import ch.epfl.scala.bsp4j.JvmRunEnvironmentParams
import ch.epfl.scala.bsp4j.JvmRunEnvironmentResult
import ch.epfl.scala.bsp4j.JvmTestEnvironmentParams
import ch.epfl.scala.bsp4j.JvmTestEnvironmentResult
import ch.epfl.scala.bsp4j.SourceItem
import ch.epfl.scala.bsp4j.SourcesItem
import ch.epfl.scala.bsp4j.SourcesParams
import ch.epfl.scala.bsp4j.SourcesResult
import com.intellij.execution.configurations.ModuleBasedConfiguration
import com.intellij.execution.configurations.RunConfiguration
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.externalSystem.util.ExternalSystemApiUtil
import com.intellij.openapi.module.Module
import com.intellij.openapi.project.Project
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiFile
import com.intellij.psi.search.GlobalSearchScope
import org.jetbrains.bsp.BspBundle
import org.jetbrains.bsp.BspUtil._
import org.jetbrains.bsp.data.BspMetadata
import org.jetbrains.bsp.protocol.BspCommunication
import org.jetbrains.bsp.protocol.BspJob
import org.jetbrains.bsp.protocol.session.BspSession.BspServer
import org.jetbrains.bsp.protocol.session.BspSession.BspSessionTask
import org.jetbrains.plugins.scala.build.BuildMessages
import org.jetbrains.plugins.scala.build.BuildToolWindowReporter
import org.jetbrains.plugins.scala.build.BuildToolWindowReporter.CancelBuildAction
import org.jetbrains.plugins.scala.extensions.inReadAction
import org.jetbrains.plugins.scala.extensions.invokeAndWait

import scala.collection.JavaConverters._
import scala.concurrent.Promise
import scala.util.Failure
import scala.util.Success
import scala.util.Try

object BspJvmEnvironment {

  trait BspTargetIdHolder {
    def currentValue: Option[BuildTargetIdentifier]
    def update(value: BuildTargetIdentifier)
  }

  case class Error(msg: String) extends RuntimeException(msg)

  type Result[A] = Either[Error, A]

  def getBspTargets(module: Module): Result[Seq[BuildTargetIdentifier]] = {
    BspMetadata.get(module.getProject, module)
      .left.map(err => Error(err.msg))
      .map(data => data.targetIds.asScala.map(id => new BuildTargetIdentifier(id.toString)))
  }

  def promptUserToSelectBspTarget(
    project: Project,
    targetIds: Seq[BuildTargetIdentifier],
    holder: BspTargetIdHolder
  ): Option[BuildTargetIdentifier] = {
    val selected = invokeAndWait(BspSelectTargetDialog.promptForBspTarget(project, targetIds, holder.currentValue))
    selected.foreach(holder.update)
    selected
  }

  def promptUserToSelectBspTargetForWorksheet(module: Module): Unit = {
    val potentialTargets = getBspTargets(module)
    potentialTargets.foreach { targetIds =>
      val holder = persistentHolderForWorksheet(module)
      promptUserToSelectBspTarget(module.getProject, targetIds, holder)
    }
  }

  def resolveForWorksheet(module: Module): Result[JvmEnvironment] = {
    val holder = persistentHolderForWorksheet(module)
    for {
      workspace <- workspaceUri(module)
      potentialTargets <- getBspTargets(module)
      selectedTarget <- holder.currentValue
        .orElse(potentialTargets.singleElement)
        .orElse(promptUserToSelectBspTarget(module.getProject, potentialTargets, holder))
        .toRight(Error(BspBundle.message("bsp.task.error.could.not.choose.any.target.id")))
      environment <- fetchJvmEnvironment(selectedTarget, workspace, module.getProject, ExecutionEnvironmentType.RUN)
    } yield environment
  }

  def resolveForRun(
    config: ModuleBasedConfiguration[_, _],
    module: Module,
    holder: BspTargetIdHolder
  ): Result[JvmEnvironment] = {
    for {
      extractor <- classExtractor(config)
      workspace <- workspaceUri(module)
      potentialTargets <- getBspTargets(module)
      targetsMatchingSources <- findTargetsMatchingSources(config, module.getProject, extractor, potentialTargets, workspace)
      selectedTarget <- holder.currentValue
        .orElse(targetsMatchingSources.singleElement)
        .orElse(potentialTargets.singleElement)
        .orElse(promptUserToSelectBspTarget(module.getProject, potentialTargets, holder))
        .toRight(Error(BspBundle.message("bsp.task.error.could.not.choose.any.target.id")))
      environment <- fetchJvmEnvironment(selectedTarget, workspace, module.getProject, extractor.environmentType)
    } yield environment
  }

  private def classExtractor(configuration: RunConfiguration) = {
    BspEnvironmentRunnerExtension.getClassExtractor(configuration)
      .toRight(Error(BspBundle.message("bsp.task.error.no.class.extractor", configuration.getClass.getName)))
  }

  private def findTargetsMatchingSources(
    configuration: RunConfiguration,
    project: Project,
    extractor: BspEnvironmentRunnerExtension,
    potentialTargets: Seq[BuildTargetIdentifier],
    workspace: URI
  ): Result[Seq[BuildTargetIdentifier]] = {
    def sourceFileForClass(className: String): Option[PsiFile] = {
      val psiFacade = JavaPsiFacade.getInstance(project)
      val scope = GlobalSearchScope.allScope(project)
      val matchedClasses = invokeAndWait(inReadAction(psiFacade.findClasses(className, scope)))
      matchedClasses match {
        case Array(matchedClass) => Option(matchedClass.getContainingFile)
        case _ => None
      }
    }

    def filterTargetsContainingSources(sourceItems: Seq[SourcesItem], files: Seq[PsiFile]): Seq[BuildTargetIdentifier] = {
      val filePaths = files.map(file => Paths.get(file.getVirtualFile.getPath))

      def sourceItemContainsAnyOfFiles(sourceItem: SourceItem): Boolean = {
        val sourcePath = Paths.get(new URI(sourceItem.getUri).getPath)
        filePaths.exists(_.startsWith(sourcePath))
      }

      sourceItems
        .filter(_.getSources.asScala.exists(sourceItemContainsAnyOfFiles))
        .map(_.getTarget)
    }

    val testClasses = extractor.classes(configuration).getOrElse(Nil)
    val testSources = testClasses.flatMap(sourceFileForClass)
    fetchSourcesForTargets(potentialTargets, workspace, project).toEither
      .map(filterTargetsContainingSources(_, testSources))
      .left.map(_ => Error(BspBundle.message("bsp.task.error.could.not.fetch.sources")))
  }

  private def persistentHolderForWorksheet(module: Module): BspTargetIdHolder = {
    PersistentBspTargetIdHolder.getInstance(module)
  }

  private def workspaceUri(module: Module): Result[URI] = {
    Option(ExternalSystemApiUtil.getExternalProjectPath(module)).map(Paths.get(_).toUri)
      .toRight(Error(BspBundle.message("bsp.task.error.could.not.extract.path", module.getName)))
  }

  private def fetchJvmEnvironment(
    target: BuildTargetIdentifier,
    workspace: URI,
    project: Project,
    environmentType: ExecutionEnvironmentType
  ): Result[JvmEnvironment] = {
    bspRequest(
      workspace, project, "bsp.task.fetching.jvm.test.environment",
      createJvmEnvironmentRequest(List(target), environmentType)
    ).flatMap {
      case Left(value) => Failure(value)
      case Right(Seq(environment)) => Success(JvmEnvironment.fromBsp(environment))
      case _ => Failure(Error(BspBundle.message("bsp.task.invalid.environment.response")))
    }.toEither.left.map {
      case e: Error => e
      case _ => Error(BspBundle.message("bsp.task.error.could.not.fetch.test.jvm.environment"))
    }
  }

  private def createJvmEnvironmentRequest(targets: Seq[BuildTargetIdentifier], environmentType: ExecutionEnvironmentType)(
    server: BspServer,
    capabilities: BuildServerCapabilities
  ): CompletableFuture[Result[Seq[JvmEnvironmentItem]]] = {
    def environment[R](
      capability: BuildServerCapabilities => java.lang.Boolean,
      endpoint: java.util.List[BuildTargetIdentifier] => CompletableFuture[R],
      items: R => java.util.List[JvmEnvironmentItem],
      endpointName: String
    ): CompletableFuture[Result[Seq[JvmEnvironmentItem]]] = {
      if (Option(capability(capabilities)).exists(_.booleanValue)) {
        endpoint(targets.asJava).thenApply(response => Right(items(response).asScala))
      } else {
        CompletableFuture.completedFuture(Left(Error(
          BspBundle.message("bsp.task.error.env.not.supported", endpointName)))
        )
      }
    }

    environmentType match {
      case ExecutionEnvironmentType.RUN =>
        environment[JvmRunEnvironmentResult](
          _.getJvmRunEnvironmentProvider,
          targets => server.jvmRunEnvironment(new JvmRunEnvironmentParams(targets)),
          _.getItems,
          "buildTarget/jvmRunEnvironment")
      case ExecutionEnvironmentType.TEST =>
        environment[JvmTestEnvironmentResult](
          _.getJvmTestEnvironmentProvider,
          targets => server.jvmTestEnvironment(new JvmTestEnvironmentParams(targets)),
          _.getItems,
          "buildTarget/jvmTestEnvironment")
    }
  }

  private def fetchSourcesForTargets(
    potentialTargets: Seq[BuildTargetIdentifier],
    workspace: URI,
    project: Project
  ): Try[Seq[SourcesItem]] = {
    bspRequest(
      workspace, project, "bsp.task.fetching.sources",
      createSourcesRequest(potentialTargets)
    ).map(sources => sources.getItems.asScala.toList)
  }

  private def createSourcesRequest(targets: Seq[BuildTargetIdentifier])(
    server: BspServer, capabilities: BuildServerCapabilities
  ): CompletableFuture[SourcesResult] = {
    server.buildTargetSources(new SourcesParams(targets.asJava))
  }

  private def bspRequest[A](workspace: URI, project: Project, messageKey: String, task: BspSessionTask[A]): Try[A] = {
    val communication = BspCommunication.forWorkspace(workspace.toFile)
    val bspTaskId = BuildMessages.randomEventId
    val cancelAction = new CancelBuildAction(Promise[Unit]())
    implicit val reporter: BuildToolWindowReporter =
      new BuildToolWindowReporter(project, bspTaskId, BspBundle.message(messageKey), cancelAction)
    val job = communication.run(
      bspSessionTask = task,
      notifications = _ => (),
      processLogger = reporter.log,
    )
    BspJob.waitForJob(job, retries = 10)
  }

  private implicit class SeqOps[A](s: Seq[A]) {
    def singleElement: Option[A] = s match {
      case Seq(single) => Some(single)
      case _ => None
    }
  }

}
