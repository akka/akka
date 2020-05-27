/*
 * Copyright (C) 2018-2020 Lightbend Inc. <https://www.lightbend.com>
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
        alias = "fixall",
        value = ";scalafixEnable;compile:scalafix;test:scalafix;multi-jvm:scalafix;scalafmtAll"),
      updateProjectCommands(
        alias = "sortImports",
        value = ";scalafixEnable;compile:scalafix SortImports;test:scalafix SortImports;multi-jvm:scalafix SortImports;scalafmtAll")
    )
}
