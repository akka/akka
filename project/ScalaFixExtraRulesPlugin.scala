/*
 * Copyright (C) 2020-2021 Lightbend Inc. <https://www.lightbend.com>
 */

package akka

import sbt.{ AutoPlugin, PluginTrigger, Plugins, ScalafixSupport }
import scalafix.sbt.ScalafixPlugin

object ScalaFixExtraRulesPlugin extends AutoPlugin with ScalafixSupport {
  override def trigger: PluginTrigger = allRequirements

  override def requires: Plugins = ScalafixPlugin

  import sbt._
  import scalafix.sbt.ScalafixPlugin.autoImport.scalafixDependencies
  override def projectSettings: Seq[Def.Setting[_]] = super.projectSettings ++ {
    scalafixDependencies in ThisBuild ++= Seq(
      "com.nequissimus" %% "sort-imports" % "0.5.5",
      // https://github.com/ohze/scala-rewrites
      // an extended version of https://github.com/scala/scala-rewrites
      "com.sandinh" %% "scala-rewrites" % "0.1.10-sd")
  }
}
