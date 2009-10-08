/**
 * Copyright (C) 2009 Scalable Solutions.
 */

package se.scalablesolutions.akka

import util.Logging

import net.lag.configgy.{Configgy, ParseException}

/**
 * @author <a href="http://jonasboner.com">Jonas Bon&#233;r</a>
 */
object Config extends Logging {
  val VERSION = "0.6"
  val HOME = {
    val systemHome = System.getenv("AKKA_HOME")
    if (systemHome == null || systemHome.length == 0) {
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
        case e: ParseException => throw new IllegalStateException("'akka.conf' config file can not be found in [" + HOME + "/config/akka.conf] - aborting. Either add it in the 'config' directory or add it to the classpath.")
      }
    } else if (System.getProperty("akka.config", "") != "") {
      val configFile = System.getProperty("akka.config", "")
      try {
        Configgy.configure(configFile)
        log.info("Config loaded from -Dakka.config=%s", configFile)
      } catch {
        case e: ParseException => throw new IllegalStateException("Config could not be loaded from -Dakka.config=" + configFile)
      }
    } else {
      try {
        Configgy.configureFromResource("akka.conf", getClass.getClassLoader)
        log.info("Config loaded from the application classpath.")
      } catch {
        case e: ParseException => throw new IllegalStateException("'$AKKA_HOME/config/akka.conf' could not be found and no 'akka.conf' can be found on the classpath - aborting. . Either add it in the '$AKKA_HOME/config' directory or add it to the classpath.")
      }
    }
    Configgy.config
  }

  val CONFIG_VERSION = config.getString("akka.version", "0")
  if (VERSION != CONFIG_VERSION) throw new IllegalStateException("Akka JAR version [" + VERSION + "] is different than the provided config ('akka.conf') version [" + CONFIG_VERSION + "]")
  val startTime = System.currentTimeMillis

  def uptime = (System.currentTimeMillis - startTime) / 1000
}
