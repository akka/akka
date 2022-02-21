/*
 * Copyright (C) 2018-2022 Lightbend Inc. <https://www.lightbend.com>
 */

package sbt
import Keys.baseDirectory

import akka.ProjectFileIgnoreSupport
import sbt.Keys.unmanagedSources

trait ScalafixSupport {
  private val ignoreConfigFileName: String = ".scalafix.conf"
  private val descriptor: String = "scalafix"

  protected def ignore(configKey: ConfigKey): Def.Setting[Task[Seq[File]]] = {
    import scalafix.sbt.ScalafixPlugin.autoImport._

    configKey / scalafix / unmanagedSources := {
      val ignoreSupport =
        new ProjectFileIgnoreSupport((ThisBuild / baseDirectory).value / ignoreConfigFileName, descriptor)

      (configKey / scalafix / unmanagedSources).value.filterNot(file => ignoreSupport.isIgnoredByFileOrPackages(file))
    }
  }

  import sbt.Keys._

  def addProjectCommandsIfAbsent(alias: String, value: String): Def.Setting[Seq[Command]] = {
    commands := {
      val currentCommands = commands.value.collect {
        case command: SimpleCommand => command.name
      }.toSet
      val isPresent = currentCommands(alias)
      if (isPresent)
        commands.value
      else
        commands.value :+ BasicCommands.newAlias(name = alias, value = value)
    }
  }

  def updateProjectCommands(alias: String, value: String): Def.Setting[Seq[Command]] = {
    commands := {
      commands.value.filterNot({
        case command: SimpleCommand => command.name == alias
        case _                      => false
      }) :+ BasicCommands.newAlias(name = alias, value = value)
    }
  }
}

object ScalafixSupport {
  def fixTestScope: Boolean = System.getProperty("akka.scalafix.fixTestScope", "false").toBoolean
}
