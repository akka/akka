/*
 * Copyright (C) 2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka

import com.typesafe.sbt.MultiJvmPlugin
import sbt.{AutoPlugin, Def, PluginTrigger, Plugins, Setting, inConfig}
import scalafix.sbt.ScalafixPlugin.autoImport.scalafixConfigSettings

object ScalafixForMultiNodeScalaTestPlugin extends AutoPlugin with ScalafixIgnoreFileSupport {
  override def trigger: PluginTrigger = allRequirements

  override def requires: Plugins = MultiNodeScalaTest
  
  import MultiJvmPlugin.autoImport._

  lazy val scalafixIgnoredSetting: Seq[Setting[_]] = Seq(
    ignore(MultiJvm)
  )

  override def projectSettings: Seq[Def.Setting[_]] =
    Seq(MultiJvm).flatMap(c => inConfig(c)(scalafixConfigSettings(c))) ++ scalafixIgnoredSetting
}
