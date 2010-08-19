/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

package se.scalablesolutions.akka.config

import se.scalablesolutions.akka.util.Logging
import se.scalablesolutions.akka.AkkaException

import net.lag.configgy.{Config => CConfig, Configgy, ParseException}

class ConfigurationException(message: String) extends AkkaException(message)

/**
 * Loads up the configuration (from the akka.conf file).
 *
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
object Config extends Logging {
  val VERSION = "0.10"

  // Set Multiverse options for max speed
  System.setProperty("org.multiverse.MuliverseConstants.sanityChecks", "false")
  System.setProperty("org.multiverse.api.GlobalStmInstance.factorymethod", "org.multiverse.stms.alpha.AlphaStm.createFast")

  val HOME = {
    val systemHome = System.getenv("AKKA_HOME")
    if (systemHome == null || systemHome.length == 0 || systemHome == ".") {
      val optionHome = System.getProperty("akka.home", "")
      if (optionHome.length != 0) Some(optionHome)
      else None
    } else Some(systemHome)
  }

  val config = {
    if (System.getProperty("akka.config", "") != "") {
      val configFile = System.getProperty("akka.config", "")
      try {
        Configgy.configure(configFile)
        log.info("Config loaded from -Dakka.config=%s", configFile)
      } catch {
        case e: ParseException => throw new ConfigurationException(
          "Config could not be loaded from -Dakka.config=" + configFile +
          "\n\tdue to: " + e.toString)
      }
      Configgy.config
    } else if (getClass.getClassLoader.getResource("akka.conf") != null) {
      try {
        Configgy.configureFromResource("akka.conf", getClass.getClassLoader)
        log.info("Config loaded from the application classpath.")
      } catch {
        case e: ParseException => throw new ConfigurationException(
          "Can't load 'akka.conf' config file from application classpath," +
          "\n\tdue to: " + e.toString)
      }
      Configgy.config
    } else if (HOME.isDefined) {
      try {
        val configFile = HOME.get + "/config/akka.conf"
        Configgy.configure(configFile)
        log.info("AKKA_HOME is defined as [%s], config loaded from [%s].", HOME.get, configFile)
      } catch {
        case e: ParseException => throw new ConfigurationException(
          "AKKA_HOME is defined as [" + HOME.get + "] " +
          "\n\tbut the 'akka.conf' config file can not be found at [" + HOME.get + "/config/akka.conf]," +
          "\n\tdue to: " + e.toString)
      }
      Configgy.config
    } else {
      log.warning(
        "\nCan't load 'akka.conf'." +
        "\nOne of the three ways of locating the 'akka.conf' file needs to be defined:" +
        "\n\t1. Define the '-Dakka.config=...' system property option." +
        "\n\t2. Put the 'akka.conf' file on the classpath." +
        "\n\t3. Define 'AKKA_HOME' environment variable pointing to the root of the Akka distribution." +
        "\nI have no way of finding the 'akka.conf' configuration file." +
        "\nUsing default values everywhere.")
      CConfig.fromString("<akka></akka>") // default empty config
    }
  }

  val CONFIG_VERSION = config.getString("akka.version", VERSION)
  if (VERSION != CONFIG_VERSION) throw new ConfigurationException(
    "Akka JAR version [" + VERSION + "] is different than the provided config ('akka.conf') version [" + CONFIG_VERSION + "]")

  val TIME_UNIT = config.getString("akka.time-unit", "seconds")

  val startTime = System.currentTimeMillis
  def uptime = (System.currentTimeMillis - startTime) / 1000
}
