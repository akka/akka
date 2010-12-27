/**
 * Copyright (C) 2009-2011 Scalable Solutions AB <http://scalablesolutions.se>
 */

package akka.config

import akka.AkkaException
import akka.util.Logging
import net.lag.configgy.{Config => CConfig, Configgy, ParseException}

import java.net.InetSocketAddress
import java.lang.reflect.Method

class ConfigurationException(message: String) extends AkkaException(message)
class ModuleNotAvailableException(message: String) extends AkkaException(message)

/**
 * Loads up the configuration (from the akka.conf file).
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
object Config extends Logging {
  val VERSION = "1.1-SNAPSHOT"

  val HOME = {
    val envHome = System.getenv("AKKA_HOME") match {
      case null | "" | "." => None
      case value           => Some(value)
    }

    val systemHome = System.getProperty("akka.home") match {
      case null | "" => None
      case value     => Some(value)
    }

    envHome orElse systemHome
  }

  val config = {

    val confName = {

      val envConf = System.getenv("AKKA_MODE") match {
        case null | "" => None
        case value     => Some(value)
      }

      val systemConf = System.getProperty("akka.mode") match {
        case null | "" => None
        case value     => Some(value)
      }

      (envConf orElse systemConf).map("akka." + _ + ".conf").getOrElse("akka.conf")
    }

    if (System.getProperty("akka.config", "") != "") {
      val configFile = System.getProperty("akka.config", "")
      try {
        Configgy.configure(configFile)
        log.slf4j.info("Config loaded from -Dakka.config={}", configFile)
      } catch {
        case e: ParseException => throw new ConfigurationException(
          "Config could not be loaded from -Dakka.config=" + configFile +
          "\n\tdue to: " + e.toString)
      }
      Configgy.config
    } else if (getClass.getClassLoader.getResource(confName) ne null) {
      try {
        Configgy.configureFromResource(confName, getClass.getClassLoader)
        log.slf4j.info("Config [{}] loaded from the application classpath.",confName)
      } catch {
        case e: ParseException => throw new ConfigurationException(
          "Can't load '" + confName + "' config file from application classpath," +
          "\n\tdue to: " + e.toString)
      }
      Configgy.config
    } else if (HOME.isDefined) {
      try {
        val configFile = HOME.get + "/config/" + confName
        Configgy.configure(configFile)
        log.slf4j.info(
          "AKKA_HOME is defined as [{}], config loaded from [{}].",
          HOME.getOrElse(throwNoAkkaHomeException),
          configFile)
      } catch {
        case e: ParseException => throw new ConfigurationException(
          "AKKA_HOME is defined as [" + HOME.get + "] " +
          "\n\tbut the 'akka.conf' config file can not be found at [" + HOME.get + "/config/"+ confName + "]," +
          "\n\tdue to: " + e.toString)
      }
      Configgy.config
    } else {
      log.slf4j.warn(
        "\nCan't load '" + confName + "'." +
        "\nOne of the three ways of locating the '" + confName + "' file needs to be defined:" +
        "\n\t1. Define the '-Dakka.config=...' system property option." +
        "\n\t2. Put the '" + confName + "' file on the classpath." +
        "\n\t3. Define 'AKKA_HOME' environment variable pointing to the root of the Akka distribution." +
        "\nI have no way of finding the '" + confName + "' configuration file." +
        "\nUsing default values everywhere.")
      CConfig.fromString("<akka></akka>") // default empty config
    }
  }
  if (config.getBool("akka.enable-jmx", true)) config.registerWithJmx("akka")

  val CONFIG_VERSION = config.getString("akka.version", VERSION)
  if (VERSION != CONFIG_VERSION) throw new ConfigurationException(
    "Akka JAR version [" + VERSION + "] is different than the provided config version [" + CONFIG_VERSION + "]")

  val TIME_UNIT = config.getString("akka.time-unit", "seconds")

  val startTime = System.currentTimeMillis
  def uptime = (System.currentTimeMillis - startTime) / 1000

  def throwNoAkkaHomeException = throw new ConfigurationException(
    "Akka home is not defined. Either:" +
    "\n\t1. Define 'AKKA_HOME' environment variable pointing to the root of the Akka distribution." +
    "\n\t2. Add the '-Dakka.home=...' option pointing to the root of the Akka distribution.")
}
