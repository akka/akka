/*
 * Copyright (C) 2019 Lightbend Inc. <https://www.lightbend.com>
 */

import akka.ProjectFileIgnoreSupport
import com.lightbend.sbt.JavaFormatterPlugin
import sbt.{AutoPlugin, PluginTrigger, Plugins}

object JavaFormatter extends AutoPlugin with ProjectFileIgnoreSupport {

  override def trigger = PluginTrigger.AllRequirements

  override def requires: Plugins = JavaFormatterPlugin

  final override protected val ignoreConfigFileName: String = ".sbt-java-formatter.conf"

  final override protected val descriptor: String = "sbt-java-formatter"

  import JavaFormatterPlugin.autoImport._
  import sbt.Keys._
  import sbt._
  import sbt.io._

  override def projectSettings: Seq[Def.Setting[_]] = Seq(
    //below is for sbt java formatter
    (excludeFilter in format) := {
      val simpleFileFilter = new SimpleFileFilter(file => isIgnoredByFileOrPackages(file))
      simpleFileFilter || (excludeFilter in format).value
    }
  )
}
