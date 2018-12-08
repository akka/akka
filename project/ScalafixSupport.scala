/*
 * Copyright (C) 2018 Lightbend Inc. <https://www.lightbend.com>
 */
package sbt

import com.typesafe.config.ConfigFactory
import sbt.Keys.unmanagedSources
import sbt.internal.sbtscalafix.Compat

trait ScalafixSupport {

  import scalafix.sbt.ScalafixPlugin.autoImport._

  protected def ignore(configKey: ConfigKey): Def.Setting[Task[Seq[File]]] = {
    lazy val stdoutLogger = Compat.ConsoleLogger(System.out)

    lazy val ignoredFiles: Set[String] = {
      import scala.collection.JavaConverters._
      val config = ConfigFactory.parseFile(file(".scalafix.conf"))
      stdoutLogger.info("ignored-files from .scalaifx.config:" + config.origin().filename())
      config
        .getStringList("ignored-files")
        .asScala
        .toSet
    }
    unmanagedSources.in(configKey, scalafix) :=
      unmanagedSources.in(configKey, scalafix).value
        .filterNot(file => {
          val ignored = ignoredFiles(file.getName)
          if (ignored) {
            stdoutLogger.info("scalafix ignored file:" + file.toURI)
          }
          ignored
        })
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
        commands.value :+ BasicCommands.newAlias(
          name = alias,
          value = value
        )
    }
  }

  def updateProjectCommands(alias: String, value: String): Def.Setting[Seq[Command]] = {
    commands := {
      commands.value.filterNot({
        case command:SimpleCommand => command.name == alias
        case _ => false
      }) :+ BasicCommands.newAlias(
          name = alias,
          value = value
        )
    }
  }
}
