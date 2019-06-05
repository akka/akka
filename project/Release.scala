/*
 * Copyright (C) 2016-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka

import sbt._
import sbt.Keys._
import java.io.File
import com.typesafe.sbt.pgp.PgpKeys.publishSigned
import sbtunidoc.BaseUnidocPlugin.autoImport.unidoc
import com.lightbend.paradox.sbt.ParadoxKeys

object Release extends ParadoxKeys {
  val releaseDirectory = SettingKey[File]("release-directory")

  lazy val settings: Seq[Setting[_]] = commandSettings ++ Seq(
    releaseDirectory := crossTarget.value / "release")

  lazy val commandSettings = Seq(
    commands ++= Seq(buildReleaseCommand, buildDocsCommand))

  def buildReleaseCommand = Command.command("buildRelease") { state =>
    val extracted = Project.extract(state)
    val release = extracted.get(releaseDirectory)
    val releaseVersion = extracted.get(version)
    val projectRef = extracted.get(thisProjectRef)
    val repo = extracted.get(Publish.defaultPublishTo)
    val state1 = extracted.runAggregated(publishSigned in projectRef, state)

    IO.delete(release)
    IO.createDirectory(release)
    IO.copyDirectory(repo, release / "releases")
    state1
  }

  def buildDocsCommand = Command.command("buildDocs") { state =>
    if (!sys.props.contains("akka.genjavadoc.enabled"))
      throw new RuntimeException("Make sure you start sbt with \"-Dakka.genjavadoc.enabled=true\" otherwise no japi will be generated")
    val extracted = Project.extract(state)
    // we want to build the api-docs and docs with the current "default" version of scala
    val scalaV = extracted.get(scalaVersion)
    val expectedScalaV = extracted.get(crossScalaVersions).head
    if (scalaV != expectedScalaV)
      throw new RuntimeException(s"The docs should be built with Scala $expectedScalaV (was $scalaV)")
    val release = extracted.get(releaseDirectory)
    val releaseVersion = extracted.get(version)
    val projectRef = extracted.get(thisProjectRef)

    val (state2, Seq(japi, api)) = extracted.runTask(unidoc in Compile, state)
    val (state3, docs) = extracted.runTask(paradox in ProjectRef(projectRef.build, "akka-docs") in Compile, state2)

    IO.delete(release / "api")
    IO.delete(release / "japi")
    IO.delete(release / "docsapi")
    IO.createDirectory(release)
    IO.copyDirectory(api, release / "api" / "akka" / releaseVersion)
    IO.copyDirectory(japi, release / "japi" / "akka" / releaseVersion)
    IO.copyDirectory(docs, release / "docs" / "akka" / releaseVersion)

    state3
  }

}
