/*
 * Copyright (C) 2009-2023 Lightbend Inc. <https://www.lightbend.com>
 */

package akka

import sbt._
import sbt.Keys._
import java.io.File

import com.lightbend.sbt.publishrsync.PublishRsyncPlugin.autoImport.publishRsyncHost
import sbt.Def

object Publish extends AutoPlugin {

  val defaultPublishTo = settingKey[File]("Default publish directory")

  override def trigger = allRequirements

  override lazy val projectSettings = Seq(
      publishRsyncHost := "akkarepo@gustav.akka.io",
      organizationName := "Lightbend Inc.",
      organizationHomepage := Some(url("https://www.lightbend.com")),
      startYear := Some(2009),
      developers := List(
          Developer(
            "akka-contributors",
            "Akka Contributors",
            "akka.official@gmail.com",
            url("https://github.com/akka/akka/graphs/contributors"))),
      publishMavenStyle := true,
      pomIncludeRepository := { x =>
        false
      },
      defaultPublishTo := target.value / "repository") ++ publishingSettings

  private lazy val publishingSettings: Seq[Def.Setting[_]] = {
    def snapshotRepoKey =
      new java.io.File(
        Option(System.getProperty("akka.gustav.key"))
          .getOrElse(System.getProperty("user.home") + "/.ssh/id_rsa_gustav.pem"))

    def cloudsmithCredentials: Seq[Credentials] = {
      val user = System.getProperty("PUBLISH_USER")
      val password = System.getProperty("PUBLISH_PASSWORD")
      if (user == null || password == null) {
        throw new Exception("Publishing credentials expected in `PUBLISH_USER` and `PUBLISH_PASSWORD`.")
      }
      Seq(Credentials("Cloudsmith API", "maven.cloudsmith.io", user, password))
    }

    Def.settings(
      publishTo := (if (isSnapshot.value)
                      Some(
                        Resolver
                          .sftp("Akka snapshots", "gustav.akka.io", "/home/akkarepo/www/snapshots")
                          .as("akkarepo", snapshotRepoKey))
                    else
                      Some("Cloudsmith API".at("https://maven.cloudsmith.io/lightbend/akka/"))),
      credentials ++= (if (isSnapshot.value) Seq[Credentials]() else cloudsmithCredentials))
  }
}

/**
 * For projects that are not to be published.
 */
object NoPublish extends AutoPlugin {
  override def requires = plugins.JvmPlugin

  override def projectSettings =
    Seq(publish / skip := true, Compile / doc / sources := Seq.empty)
}
