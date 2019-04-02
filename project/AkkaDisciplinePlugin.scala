/*
 * Copyright (C) 2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka

import sbt._
import Keys.{scalacOptions, _}
import sbt.plugins.JvmPlugin

/**
  * Initial tests found:
  * `akka-actor` 151 errors with `-Xfatal-warnings`, 6 without the flag
  */
object AkkaDisciplinePlugin extends AutoPlugin with ScalafixSupport {

  import scoverage.ScoverageKeys._
  import scalafix.sbt.ScalafixPlugin

  override def trigger: PluginTrigger = allRequirements
  override def requires: Plugins = JvmPlugin && ScalafixPlugin
  override lazy val projectSettings = disciplineSettings

  val fatalWarningsFor = Set(
    "akka-discovery",
    "akka-distributed-data",
    "akka-coordination",
    "akka-protobuf",
    "akka-stream-typed",
    "akka-cluster-typed"
  )

  val strictProjects = Set(
    "akka-discovery",
    "akka-protobuf",
    "akka-coordination"
  )

  lazy val scalaFixSettings = Seq(
    Compile / scalacOptions += "-Yrangepos")

  lazy val scoverageSettings = Seq(
    coverageMinimum := 70,
    coverageFailOnMinimum := false,
    coverageOutputHTML := true,
    coverageHighlighting := {
      import sbt.librarymanagement.{ SemanticSelector, VersionNumber }
      !VersionNumber(scalaVersion.value).matchesSemVer(SemanticSelector("<=2.11.1"))
    })

  val silencerVersion = "1.3.1"
  lazy val silencerSettings = Seq(
    libraryDependencies ++= Seq(
      compilerPlugin("com.github.ghik" %% "silencer-plugin" % silencerVersion),
      "com.github.ghik" %% "silencer-lib" % silencerVersion % Provided,
    )
  )
  
  lazy val disciplineSettings =
    scalaFixSettings ++
    silencerSettings ++
    scoverageSettings ++ Seq(
      Compile / scalacOptions ++= (if (strictProjects.contains(name.value)) {
                                 disciplineScalacOptions
                               } else {
                                 disciplineScalacOptions -- undisciplineScalacOptions
                               }).toSeq,
      Compile / scalacOptions ++= (
        if (fatalWarningsFor(name.value)) Seq("-Xfatal-warnings")
        else Seq.empty
      ),
      Compile / console / scalacOptions --= Seq("-deprecation", "-Xfatal-warnings", "-Xlint", "-Ywarn-unused:imports"),
      // Discipline is not needed for the docs compilation run (which uses
      // different compiler phases from the regular run), and in particular
      // '-Ywarn-unused:explicits' breaks 'sbt ++2.13.0-M5 akka-actor/doc'
      // https://github.com/akka/akka/issues/26119
      Compile / doc / scalacOptions --= disciplineScalacOptions.toSeq,
      Compile / scalacOptions --= (CrossVersion.partialVersion(scalaVersion.value) match {
        case Some((2, 13)) =>
          Seq(
            "-Ywarn-inaccessible",
            "-Ywarn-infer-any",
            "-Ywarn-nullary-override",
            "-Ywarn-nullary-unit",
            "-Ypartial-unification",
            "-Yno-adapted-args",
          )
        case Some((2, 12)) =>
          Nil
        case Some((2, 11)) =>
          Seq("-Ywarn-extra-implicit", "-Ywarn-unused:_")
        case _             =>
          Nil
      }),
      Compile / doc / scalacOptions ++= (CrossVersion.partialVersion(scalaVersion.value) match {
        case Some((2, 11)) =>
          Seq("-no-link-warnings")
        case _ =>
          Seq.empty
      }),
  )

  val testUndicipline = Seq(
    "-Ywarn-dead-code",  // ??? used in compile on specs
    "-Ywarn-value-discard" // Ignoring returned assertions
  )

  /**
    * Remain visibly filtered for future code quality work and removing.
    */
  val undisciplineScalacOptions = Seq(
    "-Ywarn-value-discard",
    "-Ywarn-numeric-widen",
    "-Yno-adapted-args",
  )

  /** These options are desired, but some are excluded for the time being*/
  val disciplineScalacOptions = Seq(
    // start: must currently remove, version regardless
    "-Ywarn-value-discard",
    "-Ywarn-numeric-widen",
    "-Yno-adapted-args",
    // end
    "-deprecation",
    "-Xfuture",
    "-Xlint",
    "-Ywarn-dead-code",
    "-Ywarn-inaccessible",
    "-Ywarn-infer-any",
    "-Ywarn-nullary-override",
    "-Ywarn-nullary-unit",
    "-Ywarn-unused:_",
    "-Ypartial-unification",
    "-Ywarn-extra-implicit",
  )

}
