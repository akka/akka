/*
 * Copyright (C) 2017-2018 Lightbend Inc. <http://www.lightbend.com/>
 */

package akka

import sbt._
import sbt.Keys._

object Jdk9 extends AutoPlugin {

  lazy val CompileJdk9 = config("CompileJdk9").extend(Compile)

  def notOnScala211[T](scalaBinaryVersion: String, values: Seq[T]): Seq[T] = scalaBinaryVersion match {
    case "2.11" => Seq()
    case _ => values
  }

  val compileJdk9Settings = Seq(
    // following the scala-2.12, scala-sbt-1.0, ... convention
    unmanagedSourceDirectories := notOnScala211(scalaBinaryVersion.value, Seq(
      (Compile / sourceDirectory).value / "scala-jdk-9",
      (Compile / sourceDirectory).value / "java-jdk-9"
    )),
    scalacOptions := AkkaBuild.DefaultScalacOptions ++ notOnScala211(scalaBinaryVersion.value, Seq("-release", "9")),
    javacOptions := AkkaBuild.DefaultJavacOptions ++ notOnScala211(scalaBinaryVersion.value, Seq("--release", "9"))
  )

  val compileSettings = Seq(
    // It might have been more 'neat' to add the jdk9 products to the jar via packageBin/mappings, but that doesn't work with the OSGi plugin,
    // so we add them to the fullClasspath instead.
    //    Compile / packageBin / mappings
    //      ++= (CompileJdk9 / products).value.flatMap(Path.allSubpaths),
    Compile / fullClasspath ++= (CompileJdk9 / exportedProducts).value
  )

  override def trigger = noTrigger
  override def projectConfigurations = Seq(CompileJdk9)
  override lazy val projectSettings =
    inConfig(CompileJdk9)(Defaults.compileSettings) ++
    inConfig(CompileJdk9)(compileJdk9Settings) ++
    compileSettings
}
