/*
 * Copyright (C) 2019-2021 Lightbend Inc. <https://www.lightbend.com>
 */

package akka

import com.lightbend.paradox.sbt.ParadoxPlugin
import com.lightbend.paradox.sbt.ParadoxPlugin.autoImport._
import com.lightbend.paradox.apidoc.ApidocPlugin
import com.lightbend.sbt.publishrsync.PublishRsyncPlugin.autoImport._
import sbt.Keys._
import sbt._

object Paradox {

  val propertiesSettings = Seq(
    Compile / paradoxProperties ++= Map(
        "canonical.base_url" -> "https://doc.akka.io/docs/akka/current",
        "github.base_url" -> GitHub
          .url(version.value), // for links like this: @github[#1](#1) or @github[83986f9](83986f9)
        "extref.akka.http.base_url" -> "https://doc.akka.io/docs/akka-http/current/%s",
        "extref.akka-management.base_url" -> "https://doc.akka.io/docs/akka-management/current/%s",
        "extref.platform-guide.base_url" -> "https://developer.lightbend.com/docs/akka-platform-guide/%s",
        "extref.wikipedia.base_url" -> "https://en.wikipedia.org/wiki/%s",
        "extref.github.base_url" -> (GitHub.url(version.value) + "/%s"), // for links to our sources
        "extref.samples.base_url" -> "https://developer.lightbend.com/start/?group=akka&amp;project=%s",
        "extref.ecs.base_url" -> "https://example.lightbend.com/v1/download/%s",
        "scaladoc.akka.base_url" -> "https://doc.akka.io/api/akka/2.6",
        "scaladoc.akka.http.base_url" -> "https://doc.akka.io/api/akka-http/current",
        "javadoc.java.base_url" -> "https://docs.oracle.com/en/java/javase/11/docs/api/java.base/",
        "javadoc.java.link_style" -> "direct",
        "javadoc.akka.base_url" -> "https://doc.akka.io/japi/akka/2.6",
        "javadoc.akka.link_style" -> "direct",
        "javadoc.akka.http.base_url" -> "https://doc.akka.io/japi/akka-http/current",
        "javadoc.akka.http.link_style" -> "frames",
        "scala.version" -> scalaVersion.value,
        "scala.binary.version" -> scalaBinaryVersion.value,
        "akka.version" -> version.value,
        "scalatest.version" -> Dependencies.scalaTestVersion,
        "sigar_loader.version" -> "1.6.6-rev002",
        "algolia.docsearch.api_key" -> "543bad5ad786495d9ccd445ed34ed082",
        "algolia.docsearch.index_name" -> "akka_io",
        "google.analytics.account" -> "UA-21117439-1",
        "google.analytics.domain.name" -> "akka.io",
        "signature.akka.base_dir" -> (ThisBuild / baseDirectory).value.getAbsolutePath,
        "fiddle.code.base_dir" -> (Test / sourceDirectory).value.getAbsolutePath,
        "fiddle.akka.base_dir" -> (ThisBuild / baseDirectory).value.getAbsolutePath,
        "aeron_version" -> Dependencies.aeronVersion,
        "netty_version" -> Dependencies.nettyVersion,
        "logback_version" -> Dependencies.logbackVersion))

  val rootsSettings = Seq(
    paradoxRoots := List(
        "index.html",
        // Page that recommends Alpakka:
        "camel.html",
        // TODO page not linked to
        "fault-tolerance-sample.html"))

  // FIXME https://github.com/lightbend/paradox/issues/350
  // Exclusions from direct compilation for includes dirs/files not belonging in a TOC
  val includesSettings = Seq(
    (Compile / paradoxMarkdownToHtml / excludeFilter) := (Compile / paradoxMarkdownToHtml / excludeFilter).value ||
      ParadoxPlugin.InDirectoryFilter((Compile / paradox / sourceDirectory).value / "includes"),
    // Links are interpreted relative to the page the snippet is included in,
    // instead of relative to the place where the snippet is declared.
    (Compile / paradoxMarkdownToHtml / excludeFilter) := (Compile / paradoxMarkdownToHtml / excludeFilter).value ||
      ParadoxPlugin.InDirectoryFilter((Compile / paradox / sourceDirectory).value / "includes.html"))

  val groupsSettings = Seq(Compile / paradoxGroups := Map("Language" -> Seq("Scala", "Java")))

  val settings =
    propertiesSettings ++
    rootsSettings ++
    includesSettings ++
    groupsSettings ++
    Seq(
      Compile / paradox / name := "Akka",
      resolvers += Resolver.jcenterRepo,
      ApidocPlugin.autoImport.apidocRootPackage := "akka",
      publishRsyncArtifacts += {
        val releaseVersion = if (isSnapshot.value) "snapshot" else version.value
        ((Compile / paradox).value -> s"www/docs/akka/$releaseVersion")
      })
}
