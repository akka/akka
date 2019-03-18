/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka

import sbt._
import sbtunidoc.BaseUnidocPlugin.autoImport.{unidoc, unidocProjectFilter, unidocAllSources}
import sbtunidoc.JavaUnidocPlugin.autoImport.JavaUnidoc
import sbtunidoc.ScalaUnidocPlugin.autoImport.ScalaUnidoc
import sbtunidoc.GenJavadocPlugin.autoImport._
import sbt.Keys._
import sbt.File
import scala.annotation.tailrec

import sbt.ScopeFilter.ProjectFilter

object Scaladoc extends AutoPlugin {

  object CliOptions {
    val scaladocDiagramsEnabled = CliOption("akka.scaladoc.diagrams", true)
    val scaladocAutoAPI = CliOption("akka.scaladoc.autoapi", true)
  }

  override def trigger = allRequirements
  override def requires = plugins.JvmPlugin

  val validateDiagrams = settingKey[Boolean]("Validate generated scaladoc diagrams")

  override lazy val projectSettings = {
    inTask(doc)(Seq(
      scalacOptions in Compile ++= scaladocOptions(version.value, (baseDirectory in ThisBuild).value),
      // -release caused build failures when generating javadoc:
      scalacOptions in Compile --= Seq("-release", "8"),
      autoAPIMappings := CliOptions.scaladocAutoAPI.get)) ++
      Seq(validateDiagrams in Compile := true) ++
      CliOptions.scaladocDiagramsEnabled.ifTrue(doc in Compile := {
        val docs = (doc in Compile).value
        if ((validateDiagrams in Compile).value)
          scaladocVerifier(docs)
        docs
      })
  }

  def scaladocOptions(ver: String, base: File): List[String] = {
    val urlString = GitHub.url(ver) + "/€{FILE_PATH}.scala"
    val opts = List("-implicits", "-groups", "-doc-source-url", urlString, "-sourcepath", base.getAbsolutePath)
    CliOptions.scaladocDiagramsEnabled.ifTrue("-diagrams").toList ::: opts
  }

  def scaladocVerifier(file: File): File = {
    @tailrec
    def findHTMLFileWithDiagram(dirs: Seq[File]): Boolean = {
      if (dirs.isEmpty) false
      else {
        val curr = dirs.head
        val (newDirs, files) = curr.listFiles.partition(_.isDirectory)
        val rest = dirs.tail ++ newDirs
        val hasDiagram = files exists { f =>
          val name = f.getName
          if (name.endsWith(".html") && !name.startsWith("index-") &&
            !name.equals("index.html") && !name.equals("package.html")) {
            val source = scala.io.Source.fromFile(f)(scala.io.Codec.UTF8)
            val hd = try source.getLines().exists(lines =>
              lines.contains("<div class=\"toggleContainer block diagram-container\" id=\"inheritance-diagram-container\">") ||
              lines.contains("<svg id=\"graph")
            )
            catch {
              case e: Exception => throw new IllegalStateException("Scaladoc verification failed for file '" + f + "'", e)
            } finally source.close()
            hd
          } else false
        }
        hasDiagram || findHTMLFileWithDiagram(rest)
      }
    }

    // if we have generated scaladoc and none of the files have a diagram then fail
    if (file.exists() && !findHTMLFileWithDiagram(List(file)))
      sys.error("ScalaDoc diagrams not generated!")
    else
      file
  }
}

/**
 * For projects with few (one) classes there might not be any diagrams.
 */
object ScaladocNoVerificationOfDiagrams extends AutoPlugin {

  override def trigger = noTrigger
  override def requires = Scaladoc

  override lazy val projectSettings = Seq(
    Scaladoc.validateDiagrams in Compile := false)
}

/**
 * Unidoc settings for root project. Adds unidoc command.
 */
object UnidocRoot extends AutoPlugin {

  object CliOptions {
    val genjavadocEnabled = CliOption("akka.genjavadoc.enabled", false)
  }

  object autoImport {
    val unidocRootIgnoreProjects = settingKey[Seq[ProjectReference]]("Projects to ignore when generating unidoc")
  }
  import autoImport._

  override def trigger = noTrigger
  override def requires =
    UnidocRoot.CliOptions.genjavadocEnabled.ifTrue(sbtunidoc.ScalaUnidocPlugin && sbtunidoc.JavaUnidocPlugin && sbtunidoc.GenJavadocPlugin)
      .getOrElse(sbtunidoc.ScalaUnidocPlugin)

  val akkaSettings = UnidocRoot.CliOptions.genjavadocEnabled.ifTrue(
    Seq(javacOptions in (JavaUnidoc, unidoc) := Seq("-Xdoclint:none", "--frames", "--ignore-source-errors"))).getOrElse(Nil)

  override lazy val projectSettings = {
    def unidocRootProjectFilter(ignoreProjects: Seq[ProjectReference]): ProjectFilter = 
      ignoreProjects.foldLeft(inAnyProject) { _ -- inProjects(_) }

    inTask(unidoc)(Seq(
      unidocProjectFilter in ScalaUnidoc := unidocRootProjectFilter(unidocRootIgnoreProjects.value),
      unidocProjectFilter in JavaUnidoc := unidocRootProjectFilter(unidocRootIgnoreProjects.value),
      apiMappings in ScalaUnidoc := (apiMappings in (Compile, doc)).value) ++
      UnidocRoot.CliOptions.genjavadocEnabled.ifTrue(Seq(
        // akka.stream.scaladsl.GraphDSL.Implicits.ReversePortsOps contains code that
        // genjavadoc turns into (probably incorrect) Java code that in turn confuses the javadoc tool.
        unidocAllSources in JavaUnidoc ~= { v => v.map(_.filterNot(_.getAbsolutePath.endsWith("scaladsl/GraphDSL.java"))) },
      )).getOrElse(Nil)
    )
  }
}

/**
 * Unidoc settings for every multi-project. Adds genjavadoc specific settings.
 */
object BootstrapGenjavadoc extends AutoPlugin {

  override def trigger = allRequirements
  override def requires = UnidocRoot.CliOptions.genjavadocEnabled.ifTrue(sbtunidoc.GenJavadocPlugin)
    .getOrElse(plugins.JvmPlugin)

  override lazy val projectSettings = UnidocRoot.CliOptions.genjavadocEnabled.ifTrue(
    Seq(
      unidocGenjavadocVersion := "0.11",
      scalacOptions in Compile ++= Seq("-P:genjavadoc:fabricateParams=true", "-P:genjavadoc:suppressSynthetic=false")
    )
  ).getOrElse(Nil)
}
