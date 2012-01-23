/**
 * Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.config

import akka.AkkaException

class ConfigurationException(message: String, cause: Throwable = null) extends AkkaException(message, cause){
  def this(message: String) = this(message, null)
}
class ModuleNotAvailableException(message: String, cause: Throwable = null) extends AkkaException(message, cause){
  def this(message: String) = this(message, null)
}

/**
 * Loads up the configuration (from the akka.conf file).
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
object Config {
  val VERSION = "1.3-RC7"

  val HOME = {
    val envHome = System.getenv("AKKA_HOME") match {
      case null | "" | "." ⇒ None
      case value           ⇒ Some(value)
    }

    val systemHome = System.getProperty("akka.home") match {
      case null | "" ⇒ None
      case value     ⇒ Some(value)
    }

    systemHome orElse envHome
  }

  val config: Configuration = {
    val confName = {
      val envConf = System.getenv("AKKA_MODE") match {
        case null | "" ⇒ None
        case value     ⇒ Some(value)
      }

      val systemConf = System.getProperty("akka.mode") match {
        case null | "" ⇒ None
        case value     ⇒ Some(value)
      }

      (systemConf orElse envConf).map("akka." + _ + ".conf").getOrElse("akka.conf")
    }

    val (newInstance, source) =
      if (System.getProperty("akka.config", "") != "") {
        val configFile = System.getProperty("akka.config", "")
        (() ⇒ Configuration.fromFile(configFile), "Loading config from -Dakka.config=" + configFile)
      } else if (getClass.getClassLoader.getResource(confName) ne null) {
        (() ⇒ Configuration.fromResource(confName, getClass.getClassLoader), "Loading config [" + confName + "] from the application classpath.")
      } else if (HOME.isDefined) {
        val configFile = HOME.get + "/config/" + confName
        (() ⇒ Configuration.fromFile(configFile), "AKKA_HOME is defined as [" + HOME.get + "], loading config from [" + configFile + "].")
      } else {
        (() ⇒ Configuration.fromString("akka {}"), // default empty config
          "\nCan't load '" + confName + "'." +
          "\nOne of the three ways of locating the '" + confName + "' file needs to be defined:" +
          "\n\t1. Define the '-Dakka.config=...' system property option." +
          "\n\t2. Put the '" + confName + "' file on the classpath." +
          "\n\t3. Define 'AKKA_HOME' environment variable pointing to the root of the Akka distribution." +
          "\nI have no way of finding the '" + confName + "' configuration file." +
          "\nUsing default values everywhere.")
      }

    try {
      val i = newInstance()

      val configVersion = i.getString("akka.version", VERSION)
      if (configVersion != VERSION)
        throw new ConfigurationException(
          "Akka JAR version [" + VERSION + "] is different than the provided config version [" + configVersion + "]")

      if (Configuration.outputConfigSources)
        System.out.println(source)

      i
    } catch {
      case e ⇒
        System.err.println("Couldn't parse config, fatal error.")
        System.err.println("Config source: " + source)
        e.printStackTrace(System.err)
        System.exit(-1)
        throw e
    }
  }

  val CONFIG_VERSION = config.getString("akka.version", VERSION)

  val TIME_UNIT = config.getString("akka.time-unit", "seconds")

  val startTime = System.currentTimeMillis
  def uptime = (System.currentTimeMillis - startTime) / 1000
}
