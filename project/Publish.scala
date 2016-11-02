package akka

import sbt._
import sbt.Keys._
import java.io.File
import sbtrelease.ReleasePlugin.autoImport.releasePublishArtifactsAction
import com.typesafe.sbt.pgp.PgpKeys

object Publish extends AutoPlugin {

  val defaultPublishTo = settingKey[File]("Default publish directory")

  override def trigger = allRequirements
  override def requires = sbtrelease.ReleasePlugin

  override lazy val projectSettings = Seq(
    publishTo := akkaPublishTo.value,
    credentials ++= akkaCredentials,
    publishMavenStyle := true,
    pomIncludeRepository := { x => false },
    defaultPublishTo := crossTarget.value / "repository",
    releasePublishArtifactsAction := PgpKeys.publishSigned.value,
    apiURL := {
      val apiVersion = if (isSnapshot.value) "current" else version.value
      Some(url(s"http://doc.akka.io/api/akka-http/$apiVersion/"))
    }
  )

  private def akkaPublishTo = Def.setting {
    sonatypeRepo(version.value) orElse localRepo(defaultPublishTo.value)
  }

  private def sonatypeRepo(version: String): Option[Resolver] =
    Option(sys.props("publish.maven.central")) filter (_.toLowerCase == "true") map { _ =>
      val nexus = "https://oss.sonatype.org/"
      if (version endsWith "-SNAPSHOT") "snapshots" at nexus + "content/repositories/snapshots"
      else "releases" at nexus + "service/local/staging/deploy/maven2"
    }

  private def localRepo(repository: File) =
    Some(Resolver.file("Default Local Repository", repository))

  private def akkaCredentials: Seq[Credentials] =
    Option(System.getProperty("akka.publish.credentials", null)).map(f => Credentials(new File(f))).toSeq

}

/**
 * For projects that are not published.
 */
object NoPublish extends AutoPlugin {
  override def requires = plugins.JvmPlugin

  override def projectSettings = Seq(
    publishArtifact := false,
    publish := {},
    publishLocal := {}
  )

}
