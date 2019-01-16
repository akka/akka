/*
 * Copyright (C) 2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka

import java.io.File

import com.typesafe.config.ConfigFactory
import sbt.AutoPlugin
import sbt.Def
import sbt.file
import sbt.internal.sbtscalafix.Compat

class ProjectFileIgnoreSupport(ignoreConfigFile: File, descriptor: String) {
  private val stdoutLogger = Compat.ConsoleLogger(System.out)

  private lazy val ignoreConfig = {
    require(ignoreConfigFile.exists(), s"Expected ignore configuration for $descriptor at ${ignoreConfigFile.getAbsolutePath} but was missing")
    ConfigFactory.parseFile(ignoreConfigFile)
  }

  private lazy val ignoredFiles: Set[String] = {
    import scala.collection.JavaConverters._
    stdoutLogger.debug(s"Loading ignored-files from $ignoreConfigFile:[${ignoreConfig.origin().url().toURI.getPath}]")
    ignoreConfig
      .getStringList("ignored-files")
      .asScala
      .toSet
  }

  private lazy val ignoredPackages: Set[String] = {
    import scala.collection.JavaConverters._
    stdoutLogger.debug(s"Loading ignored-packages from $ignoreConfigFile:[${ignoreConfig.origin().url().toURI.getPath}]")
    ignoreConfig
      .getStringList("ignored-packages")
      .asScala
      .toSet
  }

  def isIgnoredByFileOrPackages(file: File): Boolean =
    isIgnoredByFile(file) || isIgnoredByPackages(file)

  private def isIgnoredByFile(file: File): Boolean = {
    val ignoredByFile = ignoredFiles(file.getName)
    if (ignoredByFile) {
      stdoutLogger.debug(s"$descriptor ignored file with file name:${file.getName} file:[${file.toPath}]")
    }
    ignoredByFile
  }

  private def isIgnoredByPackages(file: File): Boolean = {
    val ignoredByPackages = ignoredPackages.exists(pkg => {
      getPackageName(file.toURI.toString) match {
        case Some(packageName) =>
          val ignored = packageName.startsWith(pkg)
          if (ignored) {
            stdoutLogger.debug(s"$descriptor ignored file with pkg:$pkg file:[${file.toPath}] ")
          }
          ignored
        case None => false
      }
    })
    ignoredByPackages
  }

  private def getPackageName(fileName: String): Option[String] = {
    def getPackageName0(fileType: String): String = {
      import java.io.{File => JFile}
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
}
