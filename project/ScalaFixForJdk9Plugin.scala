/*
 * Copyright (C) 2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka

import sbt.{AutoPlugin, PluginTrigger, Plugins, ScalafixSupport}
import scalafix.sbt.ScalafixPlugin
object ScalaFixForJdk9Plugin extends AutoPlugin with ScalafixSupport{
  override def trigger: PluginTrigger = allRequirements
  import Jdk9._
  override def requires: Plugins = Jdk9 && ScalafixPlugin

  import ScalafixPlugin.autoImport.scalafixConfigSettings
  import sbt._
  override def projectSettings: Seq[Def.Setting[_]] = super.projectSettings ++ {
    inConfig(TestJdk9)(scalafixConfigSettings(TestJdk9)) ++
    inConfig(CompileJdk9)(scalafixConfigSettings(CompileJdk9))
  }
}
