/*
 * Copyright (C) 2018 Lightbend Inc. <https://www.lightbend.com>
 */
package sbt

import java.io.{ File => JFile }

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
      stdoutLogger.info(s"Loading ignored-files from .scalaifx.config:[${config.origin().url().toURI.getPath}]")
      config
        .getStringList("ignored-files")
        .asScala
        .toSet
    }

    lazy val ignoredPackages: Set[String] = {
      import scala.collection.JavaConverters._
      val config = ConfigFactory.parseFile(file(".scalafix.conf"))
      stdoutLogger.info(s"Loading ignored-packages from .scalaifx.config:[${config.origin().url().toURI.getPath}]")
      config
        .getStringList("ignored-packages")
        .asScala
        .toSet
    }

    unmanagedSources.in(configKey, scalafix) :=
      unmanagedSources.in(configKey, scalafix).value
        .filterNot(file => {
          val ignoredByFile = ignoredFiles(file.getName)
          if (ignoredByFile) {
            stdoutLogger.info(s"scalafix ignored file: ${file.toURI} with file name: ${file.getName}")
          }
          val ignoredByPackages = ignoredPackages.exists(pkg => {
            getPackageName(file.toURI.toString) match {
              case Some(packageName) =>
                val ignored = packageName.startsWith(pkg)
                if (ignored) {
                  stdoutLogger.info(s"scalafix ignored file with pkg:$pkg file:[${file.toPath}] ")
                }
                ignored
              case None => false
            }
          })
          ignoredByFile || ignoredByPackages
        })
  }

  private def getPackageName(fileName: String): Option[String] = {
    def getPackageName0(fileType: String): String = {
      fileName.split(JFile.separatorChar)
        .dropWhile(part ⇒ part != fileType)
        .drop(1)
        .dropRight(1)
        .mkString(".")
    }

    fileName.split('.').lastOption match {
      case Some(fileType) ⇒
        fileType match {
          case "java" ⇒
            Option(getPackageName0("java"))
          case "scala" ⇒
            Option(getPackageName0("scala"))
          case _ ⇒ None
        }
      case None ⇒ None
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
        commands.value :+ BasicCommands.newAlias(
          name = alias,
          value = value
        )
    }
  }

  def updateProjectCommands(alias: String, value: String): Def.Setting[Seq[Command]] = {
    commands := {
      commands.value.filterNot({
        case command: SimpleCommand => command.name == alias
        case _ => false
      }) :+ BasicCommands.newAlias(
        name = alias,
        value = value
      )
    }
  }
}
