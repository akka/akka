/*
 * Copyright (C) 2018-2023 Lightbend Inc. <https://www.lightbend.com>
 */

package akka

import sbt.plugins.JvmPlugin
import sbt.{ AutoPlugin, PluginTrigger, Plugins, ScalafixSupport }
import scalafix.sbt.ScalafixPlugin

object ScalafixIgnoreFilePlugin extends AutoPlugin with ScalafixSupport {
  override def trigger: PluginTrigger = allRequirements

  override def requires: Plugins = JvmPlugin && ScalafixPlugin
  import sbt._
  lazy val scalafixIgnoredSetting: Seq[Setting[_]] = if (ScalafixSupport.fixTestScope) Nil else Seq(ignore(Test))

  override def projectSettings: Seq[Def.Setting[_]] =
    scalafixIgnoredSetting ++ Seq(
      addProjectCommandsIfAbsent(alias = "fixall", value = ";scalafixEnable;scalafixAll;test:compile;reload"))
}
