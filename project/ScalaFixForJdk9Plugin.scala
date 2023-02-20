/*
 * Copyright (C) 2020-2023 Lightbend Inc. <https://www.lightbend.com>
 */

package akka

import sbt.{ AutoPlugin, PluginTrigger, Plugins, ScalafixSupport }
import scalafix.sbt.ScalafixPlugin
object ScalaFixForJdk9Plugin extends AutoPlugin with ScalafixSupport {
  override def trigger: PluginTrigger = allRequirements
  import Jdk9._
  override def requires: Plugins = Jdk9 && ScalafixPlugin

  import ScalafixPlugin.autoImport.scalafixConfigSettings
  import sbt._

  lazy val scalafixIgnoredSetting: Seq[Setting[_]] = Seq(ignore(TestJdk9))

  override def projectSettings: Seq[Def.Setting[_]] =
    Seq(CompileJdk9, TestJdk9).flatMap(c => inConfig(c)(scalafixConfigSettings(c))) ++
    scalafixIgnoredSetting ++ Seq(
      updateProjectCommands(
        alias = "fixall",
        value = ";scalafixEnable;scalafixAll;scalafmtAll;test:compile;multi-jvm:compile;reload"),
      updateProjectCommands(alias = "sortImports", value = ";scalafixEnable;scalafixAll SortImports;scalafmtAll"))
}
