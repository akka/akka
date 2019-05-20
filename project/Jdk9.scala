/*
 * Copyright (C) 2017-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka

import sbt._
import sbt.Keys._

object Jdk9 extends AutoPlugin {
  import JavaVersion.notOnJdk8

  lazy val CompileJdk9 = config("CompileJdk9").extend(Compile)

  val SCALA_SOURCE_DIRECTORY = "scala-jdk-9"
  val SCALA_TEST_SOURCE_DIRECTORY = "scala-jdk9-only"
  val JAVA_SOURCE_DIRECTORY = "java-jdk-9"
  val JAVA_TEST_SOURCE_DIRECTORY = "java-jdk9-only"

  val compileJdk9Settings = Seq(
    // following the scala-2.12, scala-sbt-1.0, ... convention
    unmanagedSourceDirectories := notOnJdk8(
        Seq(
          (Compile / sourceDirectory).value / SCALA_SOURCE_DIRECTORY,
          (Compile / sourceDirectory).value / JAVA_SOURCE_DIRECTORY)),
    scalacOptions := AkkaBuild.DefaultScalacOptions ++ notOnJdk8(Seq("-release", "11")),
    javacOptions := AkkaBuild.DefaultJavacOptions ++ notOnJdk8(Seq("--release", "11")))

  val compileSettings = Seq(
    // It might have been more 'neat' to add the jdk9 products to the jar via packageBin/mappings, but that doesn't work with the OSGi plugin,
    // so we add them to the fullClasspath instead.
    //    Compile / packageBin / mappings
    //      ++= (CompileJdk9 / products).value.flatMap(Path.allSubpaths),
    Compile / fullClasspath ++= (CompileJdk9 / exportedProducts).value)

  override def trigger = noTrigger
  override def projectConfigurations = Seq(CompileJdk9)
  override lazy val projectSettings =
    inConfig(CompileJdk9)(Defaults.compileSettings) ++
    inConfig(CompileJdk9)(compileJdk9Settings) ++
    compileSettings
}
