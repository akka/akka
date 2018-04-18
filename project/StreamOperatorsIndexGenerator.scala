/**
 * Copyright (C) 2016-2018 Lightbend Inc. <https://www.lightbend.com>
 */

import sbt._
import sbt.Keys._

/**
 * Generate the "index" pages of stream operators.
 */
object StreamOperatorsIndexGenerator extends AutoPlugin {

  override val projectSettings: Seq[Setting[_]] = inConfig(Compile)(Seq(
    resourceGenerators +=
      generateAlphabeticalIndex(sourceDirectory,
        _ / "paradox" / "stream" / "operators" / "index-alphabetical.md"
      )
  ))

  def generateAlphabeticalIndex(dir: SettingKey[File], locate: File ⇒ File) = Def.task[Seq[File]] {
    val file = locate(dir.value)

    println(s"file = ${file}")
    streams.value.log.warn(s"file = ${file}")

    val defs =
      List("akka-stream/src/main/scala/akka/stream/scaladsl/Source.scala").flatMap{ f ⇒
        IO.read(new File(f)).split("\n")
          .map(_.trim).filter(_.startsWith("def "))
          .map(_.drop(4).takeWhile(c ⇒ c != '[' && c != '('))
          .map(method ⇒ s"* [Source#$method](source/$method.md)")
      }

    println(s"defs = ${defs.mkString("\n")}")

    val content =
      s"""
        |# Operators Alphabetical
        |
        |@@toc { depth=2 }
        |
        |See also [Operators by topic](index-topic.md)
        |
        |@@@ index
        |  $defs
        |@@@
      """.stripMargin

    if (!file.exists || IO.read(file) != content) IO.write(file, content)
    Seq(file)
  }

}
