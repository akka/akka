/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka

import sbt._
import sbtunidoc.Plugin.UnidocKeys._
import sbtunidoc.Plugin.{ ScalaUnidoc, JavaUnidoc, Genjavadoc, scalaJavaUnidocSettings, genjavadocExtraSettings, scalaUnidocSettings }
import sbt.Keys._
import sbt.File
import scala.annotation.tailrec

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
      scalacOptions in Compile <++= (version, baseDirectory in ThisBuild) map scaladocOptions,
      autoAPIMappings := CliOptions.scaladocAutoAPI.get
    )) ++
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
    val opts = List("-implicits", "-doc-source-url", urlString, "-sourcepath", base.getAbsolutePath)
    CliOptions.scaladocDiagramsEnabled.ifTrue("-diagrams").toList ::: opts
  }

  def scaladocVerifier(file: File): File= {
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
            val hd = try source.getLines().exists(_.contains("<div class=\"toggleContainer block diagram-container\" id=\"inheritance-diagram-container\">"))
            catch {
              case e: Exception => throw new IllegalStateException("Scaladoc verification failed for file '"+f+"'", e)
            } finally source.close()
            hd
          }
          else false
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
    Scaladoc.validateDiagrams in Compile := false
  )
}

/**
 * Unidoc settings for root project. Adds unidoc command.
 */
object UnidocRoot extends AutoPlugin {

  object CliOptions {
    val genjavadocEnabled = CliOption("akka.genjavadoc.enabled", false)
  }

  override def trigger = noTrigger

  val akkaSettings = UnidocRoot.CliOptions.genjavadocEnabled.ifTrue(Seq(
    javacOptions in (JavaUnidoc, unidoc) ++= Seq("-Xdoclint:none"),
    // genjavadoc needs to generate synthetic methods since the java code uses them
    scalacOptions += "-P:genjavadoc:suppressSynthetic=false",
    // FIXME: see #18056
    sources in(JavaUnidoc, unidoc) ~= (_.filterNot(_.getPath.contains("Access$minusControl$minusAllow$minusOrigin")))
  )).getOrElse(Nil)

  def settings(ignoreAggregates: Seq[Project], ignoreProjects: Seq[Project]) = {
    val withoutAggregates = ignoreAggregates.foldLeft(inAnyProject) { _ -- inAggregates(_, transitive = true, includeRoot = true) }
    val docProjectFilter = ignoreProjects.foldLeft(withoutAggregates) { _ -- inProjects(_) }

    inTask(unidoc)(Seq(
      unidocProjectFilter in ScalaUnidoc := docProjectFilter,
      unidocProjectFilter in JavaUnidoc := docProjectFilter,
      apiMappings in ScalaUnidoc := (apiMappings in (Compile, doc)).value
    ))
  }

  override lazy val projectSettings =
    CliOptions.genjavadocEnabled.ifTrue(scalaJavaUnidocSettings).getOrElse(scalaUnidocSettings) ++
    settings(Seq(AkkaBuild.samples), Seq(AkkaBuild.remoteTests, AkkaBuild.benchJmh, AkkaBuild.parsing, AkkaBuild.protobuf, AkkaBuild.osgiDiningHakkersSampleMavenTest, AkkaBuild.akkaScalaNightly))
}

/**
 * Unidoc settings for every multi-project. Adds genjavadoc specific settings.
 */
object Unidoc extends AutoPlugin {

  override def trigger = allRequirements
  override def requires = plugins.JvmPlugin

  override lazy val projectSettings = UnidocRoot.CliOptions.genjavadocEnabled.ifTrue(
    genjavadocExtraSettings ++ Seq(
      scalacOptions in Compile += "-P:genjavadoc:fabricateParams=true",
      unidocGenjavadocVersion in Global := "0.9",
      // FIXME: see #18056
      sources in(Genjavadoc, doc) ~= (_.filterNot(_.getPath.contains("Access$minusControl$minusAllow$minusOrigin")))
    )
  ).getOrElse(Seq.empty)
}
