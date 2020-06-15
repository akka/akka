/*
 * Copyright (C) 2019-2020 Lightbend Inc. <https://www.lightbend.com>
 */

import akka.ProjectFileIgnoreSupport
import com.lightbend.sbt.JavaFormatterPlugin
import sbt.{ AutoPlugin, PluginTrigger, Plugins }

object JavaFormatter extends AutoPlugin {

  override def trigger = PluginTrigger.AllRequirements

  override def requires: Plugins = JavaFormatterPlugin

  private val ignoreConfigFileName: String = ".sbt-java-formatter.conf"
  private val descriptor: String = "sbt-java-formatter"

  private val formatOnCompile = !sys.props.contains("akka.no.discipline")

  import JavaFormatterPlugin.autoImport._
  import sbt.Keys._
  import sbt._
  import sbt.io._

  override def projectSettings: Seq[Def.Setting[_]] =
    Seq(
      //below is for sbt java formatter
      (excludeFilter in javafmt) := {
        val ignoreSupport =
          new ProjectFileIgnoreSupport((baseDirectory in ThisBuild).value / ignoreConfigFileName, descriptor)
        val simpleFileFilter = new SimpleFileFilter(file => ignoreSupport.isIgnoredByFileOrPackages(file))
        simpleFileFilter || (excludeFilter in javafmt).value
      },
      javafmtOnCompile := formatOnCompile)
}
