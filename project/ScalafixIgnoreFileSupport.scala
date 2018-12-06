/*
 * Copyright (C) 2018 Lightbend Inc. <https://www.lightbend.com>
 */
package akka

import com.typesafe.config.ConfigFactory
import sbt.Keys.unmanagedSources
import sbt.{ConfigKey, file}
import sbt.internal.sbtscalafix.Compat

trait ScalafixIgnoreFileSupport {
  import scalafix.sbt.ScalafixPlugin.autoImport._
  protected def ignore(configKey:ConfigKey) = {
    lazy val stdoutLogger = Compat.ConsoleLogger(System.out)

    lazy val ignoredFiles:Set[String] = {
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
          if (ignored){
            stdoutLogger.info("scalafix ignored file:"+file.toURI)
          }
          ignored
        })
  }
}
