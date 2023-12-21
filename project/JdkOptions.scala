/*
 * Copyright (C) 2009-2023 Lightbend Inc. <https://www.lightbend.com>
 */

package akka

import sbt._
import sbt.librarymanagement.SemanticSelector
import sbt.librarymanagement.VersionNumber

object JdkOptions extends AutoPlugin {
  object autoImport {
    val targetSystemJdk = settingKey[Boolean](
      "Target the system JDK instead of building against JDK 11. When this is enabled resulting artifacts may not work on JDK 11!")
  }
  import autoImport._

  val specificationVersion: String = sys.props("java.specification.version")

  val isJdk11orHigher: Boolean =
    VersionNumber(specificationVersion).matchesSemVer(SemanticSelector(">=11"))
  val isJdk17orHigher: Boolean =
    VersionNumber(specificationVersion).matchesSemVer(SemanticSelector(">=17"))

  if (!isJdk11orHigher)
    throw new IllegalArgumentException("JDK 11 or higher is required")

  val versionSpecificJavaOptions =
    if (isJdk17orHigher) {
      // for aeron
      "--add-opens=java.base/sun.nio.ch=ALL-UNNAMED" ::
      // for reflective access to ForkJoinPool
      "--add-opens=java.base/java.util.concurrent=ALL-UNNAMED" ::
      // for LevelDB
      "--add-opens=java.base/java.nio=ALL-UNNAMED" :: Nil
    } else Nil

  def targetJdkScalacOptions(targetSystemJdk: Boolean, scalaVersion: String): Seq[String] = {
    if (targetSystemJdk) Nil
    else if (scalaVersion.startsWith("3.")) Seq("-Xtarget:11")
    else Seq("-release", "11")
  }

  def targetJdkJavacOptions(targetSystemJdk: Boolean): Seq[String] = {
    if (targetSystemJdk) Nil
    else Seq("--release", "11")
  }

  val targetJdkSettings = Seq(targetSystemJdk := false)
}
