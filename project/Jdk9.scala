/*
 * Copyright (C) 2017-2018 Lightbend Inc. <http://www.lightbend.com/>
 */

package akka

import sbt._
import sbt.Keys._

object Jdk9 extends AutoPlugin {

  lazy val CompileJdk9 = config("CompileJdk9").extend(Compile)

  val compileJdk9Settings = Seq(
    // following the scala-2.12, scala-sbt-1.0, ... convention
    unmanagedSourceDirectories := Seq(
      (Compile / sourceDirectory).value / "scala-jdk-9",
      (Compile / sourceDirectory).value / "java-jdk-9"
    ),
    scalacOptions := AkkaBuild.DefaultScalacOptions ++ Seq("-release", "9"),
    javacOptions := AkkaBuild.DefaultJavacOptions ++ Seq("--release", "9")
  )

  val compileSettings = Seq(
    Compile / packageBin / mappings ++=
      (CompileJdk9 / products).value.flatMap(Path.allSubpaths)
  )

  override def trigger = noTrigger
  override def projectConfigurations = Seq(CompileJdk9)
  override lazy val projectSettings =
    inConfig(CompileJdk9)(Defaults.compileSettings) ++
    inConfig(CompileJdk9)(compileJdk9Settings) ++
    compileSettings
}
