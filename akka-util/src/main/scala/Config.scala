/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

package se.scalablesolutions.akka

import util.Logging

import net.lag.configgy.{Configgy, ParseException}

/**
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
object Config extends Logging {
  val VERSION = "0.7-SNAPSHOT"

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
    if (HOME.isDefined) {
      try {
        val configFile = HOME.get + "/config/akka.conf"
        Configgy.configure(configFile)
        log.info("AKKA_HOME is defined to [%s], config loaded from [%s].", HOME.get, configFile)
      } catch {
        case e: ParseException => throw new IllegalStateException(
          "'akka.conf' config file can not be found in [" + HOME + "/config/akka.conf] aborting." +
          "\n\tEither add it in the 'config' directory or add it to the classpath.")
      }
    } else if (System.getProperty("akka.config", "") != "") {
      val configFile = System.getProperty("akka.config", "")
      try {
        Configgy.configure(configFile)
        log.info("Config loaded from -Dakka.config=%s", configFile)
      } catch {
        case e: ParseException => throw new IllegalStateException(
          "Config could not be loaded from -Dakka.config=" + configFile)
      }
    } else {
      try {
        Configgy.configureFromResource("akka.conf", getClass.getClassLoader)
        log.info("Config loaded from the application classpath.")
      } catch {
        case e: ParseException => throw new IllegalStateException(
          "\nCan't find 'akka.conf' configuration file." + 
          "\nOne of the three ways of locating the 'akka.conf' file needs to be defined:" +
          "\n\t1. Define 'AKKA_HOME' environment variable to the root of the Akka distribution." +
          "\n\t2. Define the '-Dakka.config=...' system property option." +
          "\n\t3. Put the 'akka.conf' file on the classpath." +
          "\nI have no way of finding the 'akka.conf' configuration file." +
          "\nAborting.")
      }
    }
    Configgy.config
  }

  val CONFIG_VERSION = config.getString("akka.version", "0")
  if (VERSION != CONFIG_VERSION) throw new IllegalStateException(
    "Akka JAR version [" + VERSION + "] is different than the provided config ('akka.conf') version [" + CONFIG_VERSION + "]")
  val startTime = System.currentTimeMillis

  def uptime = (System.currentTimeMillis - startTime) / 1000
}
