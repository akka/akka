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
 
  import scalafix.sbt.ScalafixPlugin

  override def trigger: PluginTrigger = allRequirements
  override def requires: Plugins = JvmPlugin && ScalafixPlugin

  override lazy val projectSettings = disciplineSettings

  lazy val scalaFixSettings = {
    import scalafix.sbt.ScalafixPlugin.autoImport._
    Seq(scalacOptions in Compile ++= Seq("-Yrangepos"))
  }

  lazy val disciplineSettings = scalaFixSettings ++ Seq(
    scalacOptions in Compile ++= disciplineScalacOptions,
    scalacOptions in Compile --= undisciplineScalacOptions,
    scalacOptions in (Compile, console) --= Seq("-Ywarn-unused:imports", "-deprecation"),
    scalacOptions in Compile --=(CrossVersion.partialVersion(scalaVersion.value) match {
      case Some((2, 13)) =>
        Seq("-Ywarn-inaccessible", "-Ywarn-infer-any", "-Ywarn-nullary-override", "-Ywarn-nullary-unit", "-Ypartial-unification", "-Yno-adapted-args")
      case Some((2, 12)) =>
        Nil//Seq("-Xfatal-warnings")    // "-Yno-adapted-args"
      case Some((2, 11)) =>
        Seq("-Ywarn-extra-implicit", "-Ywarn-unused:_")
      case _             =>
        Nil
    }))

  /**
    * Remain visibly filtered for future code quality work and removing.
    */
  val undisciplineScalacOptions = Seq(
    "-Ywarn-value-discard",
    "-Ywarn-numeric-widen",
    "-Yno-adapted-args",
    "-Xfatal-warnings")

  /** Optimistic, this is desired over time.
    * -Xlint and -Ywarn-unused: are included in commonScalaOptions.
    * If eventually all modules use this plugin, we could migrate them here.
    */
  val disciplineScalacOptions = Seq(
    // start: must currently remove, version regardless
    "-Xfatal-warnings",
    "-Ywarn-value-discard",
    "-Yno-adapted-args",
    "-Ywarn-numeric-widen",
    // end
    "-Xcheckinit",
    "-Xfuture",
    "-Ywarn-dead-code",
    "-Ywarn-inaccessible",
    "-Ywarn-infer-any",
    "-Ywarn-nullary-override",
    "-Ywarn-nullary-unit",
    "-Ywarn-unused:_",
    "-Ypartial-unification",
    "-Ywarn-extra-implicit",
    "-Ywarn-numeric-widen")

}
