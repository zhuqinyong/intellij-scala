package org.jetbrains.plugins.scala.tasty

import org.jetbrains.plugins.scala.tasty.TreePrinter.textOf
//import org.junit.Assert

import java.io.File
import java.nio.file.{FileSystems, Files, Path}
import scala.collection.mutable

// TODO Convert to unit tests (depends on https://youtrack.jetbrains.com/issue/SCL-19023)
// TODO Convert ./data to test data
object TastyReaderTest {

  def main(args: Array[String]): Unit =
    Seq(
      "member/Def",
      "member/Modifiers",
      "member/This",
      "member/Val",
      "member/Var",
//      "package1/package2/Chained",
//      "package1/package2/Flat",
      "parameter/Bounds",
      "parameter/CaseClass",
      "parameter/Class",
      "parameter/Def",
      "parameter/Enum",
      "parameter/EnumCaseClass",
      "parameter/Trait",
      "parameter/Modifiers",
      "parameter/Variance", // TODO TypeMember
      "typeDefinition/Class",
      "typeDefinition/Enum",
      "typeDefinition/ImplicitClass",
      "typeDefinition/Members",
      "typeDefinition/Modifiers",
      "typeDefinition/Object",
      "typeDefinition/Parents",
      "typeDefinition/Trait",
//      "EmptyPackage",
    ).map("community/tasty/runtime/data/" + _ + ".scala").foreach { scalaFile =>
      assertExists(scalaFile)

      val tastyFile = scalaFile.replaceFirst("\\.scala", ".tasty")
      assertExists(tastyFile)

      val tree = TreeReader.treeFrom(readBytes(tastyFile))

      val actual = textOf(tree)

      val expected = new String(readBytes(scalaFile))
        .replaceAll(raw"/\*(.*)\*/.*/\*\*/", "$1")

      val actualFile = scalaFile.replaceFirst("\\.scala", ".actual")

      if (actual != expected) {
        println(scalaFile)
        println("---Actual---")
        println(actual)
        println("---Expected---")
        println(expected)
        println("---")
        println("")
//        println(new String(readBytes(tastyFile.replaceFirst("\\.tasty", ".tree"))))

        Files.write(Path.of(actualFile), actual.getBytes)
      } else {
        if (exists(actualFile)) {
          Files.delete(Path.of(actualFile))
        }
      }

//      Assert.assertEquals(scalaFile, expected, actual)
    }

  private def assertExists(path: String): Unit = assert(exists(path), path)

  private def exists(path: String): Boolean = new File(path).exists()

  private def readBytes(file: String): Array[Byte] = Files.readAllBytes(Path.of(file))
}