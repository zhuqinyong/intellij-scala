package org.jetbrains.plugins.scala.performance

import com.intellij.openapi.application.ex.ApplicationManagerEx
import com.intellij.openapi.externalSystem.model.ProjectSystemId
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.util.registry.Registry
import com.intellij.openapi.vfs.{LocalFileSystem, VirtualFile}
import com.intellij.psi.search.{FileTypeIndex, GlobalSearchScopesCore}
import com.intellij.testFramework.fixtures.{CodeInsightTestFixture, IdeaTestFixtureFactory}
import com.intellij.testFramework.{TestLoggerFactory, VfsTestUtil}
import org.jetbrains.plugins.scala.ScalaFileType
import org.jetbrains.plugins.scala.finder.SourceFilterScope
import org.jetbrains.plugins.scala.performance.ImportingProjectTestCase.isCachingEnabled
import org.jetbrains.plugins.scala.util.reporter.ProgressReporter
import org.jetbrains.sbt.Sbt
import org.jetbrains.sbt.project.{SbtExternalSystemImportingTestCase, SbtProjectSystem}
import org.jetbrains.sbt.settings.SbtSettings
import org.junit.Assert

import java.io.File
import java.nio.file.{Files, Path, Paths}
import java.util

abstract class ImportingProjectTestCase extends SbtExternalSystemImportingTestCase {

  protected var codeInsightFixture: CodeInsightTestFixture = _

  private val projectFileName = "testHighlighting"
  private def getProjectFilePath: Path = Paths.get(projectDirPath, s"$projectFileName.ipr")
  private def isProjectAlreadyCached = Files.exists(getProjectFilePath)

  override def setUpFixtures(): Unit = {
    val factory = IdeaTestFixtureFactory.getFixtureFactory
    val projectFixture =
      if (isProjectAlreadyCached && isCachingEnabled)
        new FixtureDelegate(getProjectFilePath)
      else
        factory.createFixtureBuilder(getName).getFixture
    codeInsightFixture = factory.createCodeInsightFixture(projectFixture)
    codeInsightFixture.setUp()
    myTestFixture = codeInsightFixture
  }

  private def persistProjectConfiguration(): Unit = {
    Option(myProject.getProjectFile).foreach { projectFile =>
      Files.copy(projectFile.toNioPath, getProjectFilePath)
    }
    Option(myProject.getWorkspaceFile).foreach { workspaceFile =>
      if (workspaceFile.exists())
        Files.copy(myProject.getWorkspaceFile.toNioPath, Paths.get(projectDirPath, s"$projectFileName.iws"))
    }
  }

  override protected def tearDownFixtures(): Unit = {
    if (!isProjectAlreadyCached && isCachingEnabled)
      persistProjectConfiguration()
    codeInsightFixture.tearDown()
    codeInsightFixture = null
    myTestFixture = null
  }

  override protected def getExternalSystemConfigFileName: String = Sbt.BuildFile

  def filesWithProblems: Map[String, Set[TextRange]] = Map.empty

  protected val reporter = ProgressReporter.newInstance(getClass.getSimpleName, filesWithProblems)

  override protected def getExternalSystemId: ProjectSystemId = SbtProjectSystem.Id

  override protected def getTestsTempDir: String = ""

  def rootDirPath: String

  def projectName: String

  def projectDirPath: String = s"$rootDirPath/$projectName"

  def doBeforeImport(): Unit = {}

  override def setUpInWriteAction(): Unit = {
    super.setUpInWriteAction()

    doBeforeImport()

    val projectDir = new File(projectDirPath)

    myProjectRoot = LocalFileSystem.getInstance.refreshAndFindFileByIoFile(projectDir)

    reporter.notify("Finished sbt setup, starting import")
  }

  private def forceSaveProject(): Unit = {
    val app = ApplicationManagerEx.getApplicationEx
    try {
      app.setSaveAllowed(true)
      myProject.save()
    } finally {
      app.setSaveAllowed(false)
    }
  }

  override def setUp(): Unit = dumpLogsOnException {
    super.setUp()

    Registry.get("ast.loading.filter").setValue(true, getTestRootDisposable)

    if (isProjectAlreadyCached && isCachingEnabled) {
      reporter.notify("Project caching enabled, reusing last sbt import")
    } else {
      importProject(false)
    }
    if (isCachingEnabled)
      forceSaveProject()

    val sbtSettings = SbtSettings.getInstance(myProject)
    sbtSettings.setVmParameters(sbtSettings.vmParameters + s" -Dsbt.ivy.home=$rootDirPath/.ivy_cache")
  }

  /**
   * This methods basically duplicates log dumping logic from [[com.intellij.testFramework.UsefulTestCase#wrapTestRunnable]].
   * By default logs are not dumped in there was a failure in `setUp` method. But wee need them to debug failures
   * during project importing process, which is done in `setUp` method.
   *
   * Actually, I wish this logic was implemented in the platform (both for `setUp` & `tearDown` methods).
   * If there is a willing we might discuss it with IntelliJ platform team.
   */
  private def dumpLogsOnException(body: => Unit): Unit = {
    val testDescription = "dummy test description"
    TestLoggerFactory.onTestStarted()

    var success = true
    try {
      body
    } catch {
      case t: Throwable =>
        success = false
        throw t
    }
    finally {
      TestLoggerFactory.onTestFinished(success, testDescription)
    }
  }

  protected def findFile(filename: String): VirtualFile = {
    import scala.jdk.CollectionConverters._
    val searchScope = SourceFilterScope(GlobalSearchScopesCore.directoryScope(myProject, myProjectRoot, true))(myProject)

    val files: util.Collection[VirtualFile] = FileTypeIndex.getFiles(ScalaFileType.INSTANCE, searchScope)
    val file = files.asScala.filter(_.getName == filename).toList match {
      case vf :: Nil => vf
      case Nil => // is this a file path?
        val file = VfsTestUtil.findFileByCaseSensitivePath(s"$projectDirPath/$filename")
        Assert.assertTrue(
          s"Could not find file: $filename. Consider providing relative path from project root",
          file != null && files.contains(file)
        )
        file
      case list =>
        Assert.fail(s"There are ${list.size} files with name $filename. Provide full path from project root")
        null
    }
    LocalFileSystem.getInstance().refreshFiles(files)
    file
  }
}
object ImportingProjectTestCase {
  private final val isCachingEnabled = !sys.props.get("project.highlighting.disable.cache").contains("true")
}
