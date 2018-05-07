/*
 * Copyright (C) 2017-2018 Lightbend Inc. <https://www.lightbend.com>
 */

import java.io.File

import sbt._
import sbt.Keys._

object Jdk9CompileDirectoriesPlugin extends AutoPlugin {

  val jdkVersion: String = System.getProperty("java.version")

  override def trigger = allRequirements

  override lazy val projectSettings = Seq(

    javacOptions in Compile ++= {
      // making sure we're really targeting 1.8
      if (isJDK9) Seq("-target", "1.8", "-source", "1.8", "-Xdoclint:none")
      else Seq("-Xdoclint:none")
    },

    unmanagedSourceDirectories in Compile ++= {
      if (isJDK9) {
        println(s"[JDK9] Enabled [...-jdk9-only] directories to be compiled.")
        Seq(
          (sourceDirectory in Compile).value / "java-jdk9-only",
          (sourceDirectory in Compile).value / "scala-jdk9-only")
      } else Seq.empty
    },

    unmanagedSourceDirectories in Test ++= {
      if (isJDK9) {
        Seq(
          (sourceDirectory in Test).value / "java-jdk9-only",
          (sourceDirectory in Test).value / "scala-jdk9-only")
      } else Seq.empty
    })

  private def isJDK9 = {
    jdkVersion startsWith "9"
  }
}
