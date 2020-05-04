/*
 * Copyright (C) 2009-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka

import sbt._
import sbt.Keys._
import java.io.File
import sbtwhitesource.WhiteSourcePlugin.autoImport.whitesourceIgnore
import com.lightbend.sbt.publishrsync.PublishRsyncPlugin.autoImport.publishRsyncHost

object Publish extends AutoPlugin {

  val defaultPublishTo = settingKey[File]("Default publish directory")

  override def trigger = allRequirements

  override lazy val projectSettings = Seq(
    pomExtra := akkaPomExtra,
    publishTo := Some(akkaPublishTo.value),
    publishRsyncHost := "akkarepo@gustav.akka.io",
    credentials ++= akkaCredentials,
    organizationName := "Lightbend Inc.",
    organizationHomepage := Some(url("https://www.lightbend.com")),
    publishMavenStyle := true,
    pomIncludeRepository := { x =>
      false
    },
    defaultPublishTo := target.value / "repository")

  def akkaPomExtra = {
    <inceptionYear>2009</inceptionYear>
    <developers>
      <developer>
        <id>akka-contributors</id>
        <name>Akka Contributors</name>
        <email>akka-dev@googlegroups.com</email>
        <url>https://github.com/akka/akka/graphs/contributors</url>
      </developer>
    </developers>
  }

  private def akkaPublishTo = Def.setting {
    val key = new java.io.File(
      Option(System.getProperty("akka.gustav.key")).getOrElse(System.getProperty("user.home") + "/.ssh/id_rsa_gustav.pem"))
    if (isSnapshot.value)
      Resolver.sftp("Akka snapshots", "gustav.akka.io", "/home/akkarepo/www/snapshots").as("akkarepo", key)
    else
      Opts.resolver.sonatypeStaging
  }

  private def akkaCredentials: Seq[Credentials] =
    Option(System.getProperty("akka.publish.credentials")).map(f => Credentials(new File(f))).toSeq
}

/**
 * For projects that are not to be published.
 */
object NoPublish extends AutoPlugin {
  override def requires = plugins.JvmPlugin

  override def projectSettings =
    Seq(skip in publish := true, sources in (Compile, doc) := Seq.empty, whitesourceIgnore := true)
}
