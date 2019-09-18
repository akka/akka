/*
 * Copyright (C) 2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka

import sbt._
import Keys.{ scalacOptions, _ }
import sbt.plugins.JvmPlugin

object AkkaDisciplinePlugin extends AutoPlugin with ScalafixSupport {

  import scoverage.ScoverageKeys._
  import scalafix.sbt.ScalafixPlugin

  override def trigger: PluginTrigger = allRequirements
  override def requires: Plugins = JvmPlugin && ScalafixPlugin
  override lazy val projectSettings = disciplineSettings

  // We allow warnings in docs to get the 'snippets' right
  val nonFatalWarningsFor = Set("akka-docs")

  val strictProjects = Set("akka-discovery", "akka-protobuf", "akka-coordination")

  lazy val scalaFixSettings = Seq(Compile / scalacOptions += "-Yrangepos")

  lazy val scoverageSettings =
    Seq(coverageMinimum := 70, coverageFailOnMinimum := false, coverageOutputHTML := true, coverageHighlighting := true)

  lazy val silencerSettings = {
    val silencerVersion = "1.4.2"
    Seq(
      libraryDependencies ++= Seq(
          compilerPlugin("com.github.ghik" %% "silencer-plugin" % silencerVersion),
          "com.github.ghik" %% "silencer-lib" % silencerVersion % Provided))
  }

  lazy val disciplineSettings =
    scalaFixSettings ++
    silencerSettings ++
    scoverageSettings ++ Seq(
      Compile / scalacOptions ++= (
          if (!nonFatalWarningsFor(name.value)) Seq("-Xfatal-warnings")
          else Seq.empty
        ),
      Test / scalacOptions --= testUndicipline,
      Compile / console / scalacOptions --= Seq("-deprecation", "-Xfatal-warnings", "-Xlint", "-Ywarn-unused:imports"),
      Compile / scalacOptions ++= (CrossVersion.partialVersion(scalaVersion.value) match {
          case Some((2, 13)) =>
            disciplineScalacOptions -- Set(
              "-Ywarn-inaccessible",
              "-Ywarn-infer-any",
              "-Ywarn-nullary-override",
              "-Ywarn-nullary-unit",
              "-Ypartial-unification",
              "-Yno-adapted-args")
          case Some((2, 12)) =>
            disciplineScalacOptions
          case _ =>
            Nil
        }).toSeq,
      Compile / scalacOptions --=
        (if (strictProjects.contains(name.value)) Seq.empty
         else undisciplineScalacOptions.toSeq),
      // Discipline is not needed for the docs compilation run (which uses
      // different compiler phases from the regular run), and in particular
      // '-Ywarn-unused:explicits' breaks 'sbt ++2.13.0-M5 akka-actor/doc'
      // https://github.com/akka/akka/issues/26119
      Compile / doc / scalacOptions --= disciplineScalacOptions.toSeq :+ "-Xfatal-warnings")

  val testUndicipline = Seq(
    "-Ywarn-dead-code", // ??? used in compile only specs
    "-Ywarn-value-discard" // Ignoring returned assertions
  )

  /**
   * Remain visibly filtered for future code quality work and removing.
   */
  val undisciplineScalacOptions = Set("-Ywarn-value-discard", "-Ywarn-numeric-widen", "-Yno-adapted-args")

  /** These options are desired, but some are excluded for the time being*/
  val disciplineScalacOptions = Set(
    // start: must currently remove, version regardless
    "-Ywarn-value-discard",
    "-Ywarn-numeric-widen",
    "-Yno-adapted-args",
    // end
    "-deprecation",
    "-Xlint",
    "-Ywarn-dead-code",
    "-Ywarn-inaccessible",
    "-Ywarn-infer-any",
    "-Ywarn-nullary-override",
    "-Ywarn-nullary-unit",
    "-Ywarn-unused:_",
    "-Ypartial-unification",
    "-Ywarn-extra-implicit")

}
