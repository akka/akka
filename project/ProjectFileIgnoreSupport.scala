/*
 * Copyright (C) 2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka

import java.io.File

import com.typesafe.config.ConfigFactory
import sbt.file
import sbt.internal.sbtscalafix.Compat

trait ProjectFileIgnoreSupport extends ProjectFileSupport {
  protected val stdoutLogger = Compat.ConsoleLogger(System.out)

  protected def ignoreFileName: String

  protected def descriptor: String

  lazy val ignoredFiles: Set[String] = {
    import scala.collection.JavaConverters._
    val config = ConfigFactory.parseFile(file(ignoreFileName))
    stdoutLogger.info(s"Loading ignored-files from $ignoreFileName:[${config.origin().url().toURI.getPath}]")
    config
      .getStringList("ignored-files")
      .asScala
      .toSet
  }

  lazy val ignoredPackages: Set[String] = {
    import scala.collection.JavaConverters._
    val config = ConfigFactory.parseFile(file(ignoreFileName))
    stdoutLogger.info(s"Loading ignored-packages from $ignoreFileName:[${config.origin().url().toURI.getPath}]")
    config
      .getStringList("ignored-packages")
      .asScala
      .toSet
  }

  protected def isIgnoredByFile(file: File): Boolean = {
    val ignoredByFile = ignoredFiles(file.getName)
    if (ignoredByFile) {
      stdoutLogger.info(s"$descriptor ignored file with file name:${file.getName} file:[${file.toPath}]")
    }
    ignoredByFile
  }

  protected def isIgnoredByPackages(file: File): Boolean = {
    val ignoredByPackages = ignoredPackages.exists(pkg => {
      getPackageName(file.toURI.toString) match {
        case Some(packageName) =>
          val ignored = packageName.startsWith(pkg)
          if (ignored) {
            stdoutLogger.info(s"$descriptor ignored file with pkg:$pkg file:[${file.toPath}] ")
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
}
