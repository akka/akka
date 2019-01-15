/*
 * Copyright (C) 2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka

import java.io.File

import com.typesafe.config.ConfigFactory
import sbt.file
import sbt.internal.sbtscalafix.Compat

trait ProjectFileIgnoreSupport {
  protected val stdoutLogger = Compat.ConsoleLogger(System.out)

  protected def ignoreConfigFileName: String

  protected def descriptor: String

  lazy val ignoredFiles: Set[String] = {
    import scala.collection.JavaConverters._
    val config = ConfigFactory.parseFile(file(ignoreConfigFileName))
    stdoutLogger.debug(s"Loading ignored-files from $ignoreConfigFileName:[${config.origin().url().toURI.getPath}]")
    config
      .getStringList("ignored-files")
      .asScala
      .toSet
  }

  lazy val ignoredPackages: Set[String] = {
    import scala.collection.JavaConverters._
    val config = ConfigFactory.parseFile(file(ignoreConfigFileName))
    stdoutLogger.debug(s"Loading ignored-packages from $ignoreConfigFileName:[${config.origin().url().toURI.getPath}]")
    config
      .getStringList("ignored-packages")
      .asScala
      .toSet
  }

  protected def isIgnoredByFile(file: File): Boolean = {
    val ignoredByFile = ignoredFiles(file.getName)
    if (ignoredByFile) {
      stdoutLogger.debug(s"$descriptor ignored file with file name:${file.getName} file:[${file.toPath}]")
    }
    ignoredByFile
  }

  protected def isIgnoredByPackages(file: File): Boolean = {
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

  protected def isIgnoredByFileOrPackages(file: File): Boolean = {
    isIgnoredByFile(file) || isIgnoredByPackages(file)
  }

  protected def getPackageName(fileName: String): Option[String] = {
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
