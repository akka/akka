/*
 * Copyright (C) 2018-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka

import com.typesafe.sbt.MultiJvmPlugin
import sbt.{AutoPlugin, Def, PluginTrigger, Plugins, ScalafixSupport, Setting, inConfig}
import scalafix.sbt.ScalafixPlugin.autoImport.scalafixConfigSettings

object ScalafixForMultiNodePlugin extends AutoPlugin with ScalafixSupport {
  override def trigger: PluginTrigger = allRequirements

  override def requires: Plugins = MultiNode

  import MultiJvmPlugin.autoImport._

  lazy val scalafixIgnoredSetting: Seq[Setting[_]] = Seq(
    ignore(MultiJvm)
  )

  override def projectSettings: Seq[Def.Setting[_]] =
    Seq(MultiJvm).flatMap(c => inConfig(c)(scalafixConfigSettings(c))) ++
      scalafixIgnoredSetting ++ Seq(
      updateProjectCommands(
        alias = "fix",
        value = ";scalafixEnable;compile:scalafix;test:scalafix;multi-jvm:scalafix;test:compile;reload"))
}
