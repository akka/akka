/*
 * Copyright (C) 2019-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka

import sbt._
import Keys.{ scalacOptions, _ }
import sbt.plugins.JvmPlugin

object AkkaDisciplinePlugin extends AutoPlugin {

  override def trigger: PluginTrigger = allRequirements
  override def requires: Plugins = JvmPlugin
  override lazy val projectSettings = disciplineSettings

  // allow toggling for pocs/exploration of ideas without discpline
  val enabled = !sys.props.contains("akka.no.discipline")

  // We allow warnings in docs to get the 'snippets' right
  val nonFatalWarningsFor = Set("akka-docs")

  val looseProjects = Set(
    "akka-actor",
    "akka-actor-testkit-typed",
    "akka-actor-tests",
    "akka-actor-typed",
    "akka-actor-typed-tests",
    "akka-bench-jmh",
    "akka-cluster",
    "akka-cluster-metrics",
    "akka-cluster-sharding",
    "akka-distributed-data",
    "akka-persistence",
    "akka-persistence-tck",
    "akka-persistence-typed",
    "akka-persistence-query",
    "akka-remote",
    "akka-remote-tests",
    "akka-stream",
    "akka-stream-testkit",
    "akka-stream-tests",
    "akka-stream-tests-tck",
    "akka-testkit")

  lazy val silencerSettings = {
    val silencerVersion = "1.6.0"
    Seq(
      libraryDependencies ++= Seq(
          compilerPlugin(("com.github.ghik" %% "silencer-plugin" % silencerVersion).cross(CrossVersion.patch)),
          ("com.github.ghik" %% "silencer-lib" % silencerVersion % Provided).cross(CrossVersion.patch)))
  }

  lazy val disciplineSettings =
    if (enabled) {
      silencerSettings ++ Seq(
        Compile / scalacOptions ++= (
            if (!nonFatalWarningsFor(name.value)) Seq("-Xfatal-warnings")
            else Seq.empty
          ),
        Test / scalacOptions --= testUndicipline,
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
          (if (looseProjects.contains(name.value)) undisciplineScalacOptions.toSeq
           else Seq.empty),
        // Discipline is not needed for the docs compilation run (which uses
        // different compiler phases from the regular run), and in particular
        // '-Ywarn-unused:explicits' breaks 'sbt ++2.13.0-M5 akka-actor/doc'
        // https://github.com/akka/akka/issues/26119
        Compile / doc / scalacOptions --= disciplineScalacOptions.toSeq :+ "-Xfatal-warnings",
        // having discipline warnings in console is just an annoyance
        Compile / console / scalacOptions --= disciplineScalacOptions.toSeq)
    } else {
      // we still need these in opt-out since the annotations are present
      silencerSettings ++ Seq(Compile / scalacOptions += "-deprecation")
    }

  val testUndicipline = Seq("-Ywarn-dead-code" // '???' used in compile only specs
  )

  /**
   * Remain visibly filtered for future code quality work and removing.
   */
  val undisciplineScalacOptions = Set("-Ywarn-numeric-widen")

  /** These options are desired, but some are excluded for the time being*/
  val disciplineScalacOptions = Set(
    "-Ywarn-numeric-widen",
    "-Yno-adapted-args",
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
