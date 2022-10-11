/*
 * Copyright (C) 2019-2022 Lightbend Inc. <https://www.lightbend.com>
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
  val nonFatalJavaWarningsFor = Set(
    // for sun.misc.Unsafe and AbstractScheduler
    "akka-actor",
    // references to deprecated PARSER fields in generated message formats?
    "akka-actor-typed-tests",
    // references to deprecated PARSER fields in generated message formats?
    "akka-cluster-typed",
    // use of deprecated akka.protobuf.GeneratedMessage
    "akka-protobuf",
    "akka-protobuf-v3",
    // references to deprecated PARSER fields in generated message formats?
    "akka-remote",
    // references to deprecated PARSER fields in generated message formats?
    "akka-distributed-data",
    // references to deprecated PARSER fields in generated message formats?
    "akka-cluster-sharding-typed",
    // references to deprecated PARSER fields in generated message formats?
    "akka-persistence-typed",
    // references to deprecated PARSER fields in generated message formats?
    "akka-persistence-query",
    "akka-docs",
    // use varargs of `Graph` in alsoTo and etc operators
    "akka-stream-tests")

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
    "akka-cluster-sharding-typed",
    "akka-distributed-data",
    "akka-docs",
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

  val defaultScalaOptions = "-Wconf:cat=unused-nowarn:s,cat=lint-infer-any:s,any:e"

  lazy val nowarnSettings = Seq(
    Compile / scalacOptions ++= (
        if (scalaVersion.value.startsWith("3.")) Nil
        else Seq(defaultScalaOptions)
      ),
    Test / scalacOptions ++= (
        if (scalaVersion.value.startsWith("3.")) Nil
        else Seq(defaultScalaOptions)
      ),
    Compile / doc / scalacOptions := Seq())

  /**
   * We are a little less strict in docs
   */
  val docs =
    Seq(
      Compile / scalacOptions -= defaultScalaOptions,
      Compile / scalacOptions ++= (
          if (scalaVersion.value.startsWith("3.")) Nil
          else Seq("-Wconf:cat=unused:s,cat=deprecation:s,cat=unchecked:s,any:e")
        ),
      Test / scalacOptions --= Seq("-Xlint", "-unchecked", "-deprecation"),
      Test / scalacOptions -= defaultScalaOptions,
      Test / scalacOptions ++= (
          if (scalaVersion.value.startsWith("3.")) Nil
          else Seq("-Wconf:cat=unused:s,cat=deprecation:s,cat=unchecked:s,any:e")
        ),
      Compile / doc / scalacOptions := Seq())

  lazy val disciplineSettings =
    if (enabled) {
      nowarnSettings ++ Seq(
        Compile / scalacOptions ++= Seq("-Xfatal-warnings"),
        Test / scalacOptions --= testUndiscipline,
        Compile / javacOptions ++= (
            if (scalaVersion.value.startsWith("3.")) {
              Seq()
            } else {
              if (!nonFatalJavaWarningsFor(name.value)) Seq("-Werror", "-Xlint:deprecation", "-Xlint:unchecked")
              else Seq.empty
            }
          ),
        Compile / doc / javacOptions := Seq("-Xdoclint:none"),
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
        Compile / console / scalacOptions --= disciplineScalacOptions.toSeq,
        Test / console / scalacOptions --= disciplineScalacOptions.toSeq)
    } else {
      // we still need these in opt-out since the annotations are present
      nowarnSettings ++ Seq(Compile / scalacOptions += "-deprecation")
    }

  val testUndiscipline = Seq("-Ywarn-dead-code" // '???' used in compile only specs
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
