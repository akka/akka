package akka

import sbt._
import sbt.Keys._
import java.io.File
import com.typesafe.sbt.site.SphinxSupport.{ generate, Sphinx }

object Release {
  val releaseDirectory = SettingKey[File]("release-directory")

  lazy val settings: Seq[Setting[_]] = commandSettings ++ Seq(
    releaseDirectory <<= crossTarget / "release"
  )

  lazy val commandSettings = Seq(
    commands += buildReleaseCommand
  )

  def buildReleaseCommand = Command.command("build-release") { state =>
    val extracted = Project.extract(state)
    val release = extracted.get(releaseDirectory)
    val releaseVersion = extracted.get(version)
    val projectRef = extracted.get(thisProjectRef)
    val repo = extracted.get(Publish.defaultPublishTo)
    val state1 = extracted.runAggregated(publish in projectRef, state)
    val (state2, api) = extracted.runTask(Unidoc.unidoc, state1)
    val (state3, docs) = extracted.runTask(generate in Sphinx, state2)
    val (state4, dist) = extracted.runTask(Dist.dist, state3)
    IO.delete(release)
    IO.createDirectory(release)
    IO.copyDirectory(repo, release / "releases")
    IO.copyDirectory(api, release / "api" / "akka" / releaseVersion)
    IO.copyDirectory(docs, release / "docs" / "akka" / releaseVersion)
    IO.copyFile(dist, release / "downloads" / dist.name)
    state4
  }
}
