/*
 * Copyright (C) 2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka

import com.typesafe.sbt.MultiJvmPlugin.MultiJvmKeys.MultiJvm
import sbt.{AutoPlugin, Def, PluginTrigger, Plugins, inConfig}
import scalafix.sbt.ScalafixPlugin.autoImport.scalafixConfigSettings

object ScalafixForMultiNodeScalaTestPlugin extends AutoPlugin {
  override def trigger: PluginTrigger = allRequirements

  override def requires: Plugins = MultiNodeScalaTest

  override def projectSettings: Seq[Def.Setting[_]] =
    Seq(MultiJvm).flatMap(c => inConfig(c)(scalafixConfigSettings(c)))

}
