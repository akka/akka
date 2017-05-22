/**
 * Copyright (C) 2016-2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka

import sbt._
import sbt.Keys._
import java.io.File
import com.typesafe.sbt.pgp.PgpKeys.publishSigned
import sbtunidoc.Plugin.UnidocKeys._
import com.lightbend.paradox.sbt.ParadoxKeys

object Release extends ParadoxKeys {
  val releaseDirectory = SettingKey[File]("release-directory")

  lazy val settings: Seq[Setting[_]] = commandSettings ++ Seq(
    releaseDirectory := crossTarget.value / "release"
  )

  lazy val commandSettings = Seq(
    commands += buildReleaseCommand
  )

  def buildReleaseCommand = Command.command("buildRelease") { state =>
    val extracted = Project.extract(state)
    val release = extracted.get(releaseDirectory)
    val releaseVersion = extracted.get(version)
    val projectRef = extracted.get(thisProjectRef)
    val repo = extracted.get(Publish.defaultPublishTo)
    val state1 = extracted.runAggregated(publishSigned in projectRef, state)
    val (state2, Seq(api, japi)) = extracted.runTask(unidoc in Compile, state1)
    val (state3, docs) = extracted.runTask(paradox in ProjectRef(projectRef.build, "akka-docs") in Compile, state2)

    IO.delete(release)
    IO.createDirectory(release)
    IO.copyDirectory(repo, release / "releases")
    IO.copyDirectory(api, release / "api" / "akka" / releaseVersion)
    IO.copyDirectory(japi, release / "japi" / "akka" / releaseVersion)
    IO.copyDirectory(docs, release / "docs" / "akka" / releaseVersion)

    state3
  }

}
