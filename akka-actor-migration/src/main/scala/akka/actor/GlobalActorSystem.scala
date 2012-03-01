/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.actor

import java.io.File
import com.typesafe.config.Config
import com.typesafe.config.ConfigFactory
import com.typesafe.config.ConfigParseOptions
import akka.util.Timeout
import akka.util.duration._

@deprecated("use ActorSystem instead", "2.0")
object GlobalActorSystem extends ActorSystemImpl("GlobalSystem", OldConfigurationLoader.defaultConfig, OldConfigurationLoader.oldClassLoader) {
  start()

  /**
   * Timeout used in `OldFuture.get` and default implicit ask timeout.
   * Hard coded since the migration kit is not intended to be used for production anyway.
   */
  val AwaitTimeout = Timeout(5 seconds)
}

/**
 * Loads configuration (akka.conf) from same location as Akka 1.x
 */
@deprecated("use default config location or write your own configuration loader", "2.0")
object OldConfigurationLoader {
  val oldClassLoader: ClassLoader = ActorSystem.findClassLoader(1)

  val defaultConfig: Config = {
    val cfg = fromProperties orElse fromClasspath orElse fromHome getOrElse emptyConfig
    val config = cfg.withFallback(ConfigFactory.defaultReference(oldClassLoader))
    config.checkValid(ConfigFactory.defaultReference(oldClassLoader), "akka")
    config
  }

  // file extensions (.conf, .json, .properties), are handled by parseFileAnySyntax
  val defaultLocation: String = (systemMode orElse envMode).map("akka." + _).getOrElse("akka")

  private def envMode = System.getenv("AKKA_MODE") match {
    case null | "" ⇒ None
    case value     ⇒ Some(value)
  }

  private def systemMode = System.getProperty("akka.mode") match {
    case null | "" ⇒ None
    case value     ⇒ Some(value)
  }

  private def configParseOptions = ConfigParseOptions.defaults.setAllowMissing(false)

  private def fromProperties = try {
    val property = Option(System.getProperty("akka.config"))
    property.map(p ⇒
      ConfigFactory.systemProperties.withFallback(
        ConfigFactory.parseFileAnySyntax(new File(p), configParseOptions)))
  } catch { case _ ⇒ None }

  private def fromClasspath = try {
    Option(ConfigFactory.systemProperties.withFallback(
      ConfigFactory.parseResourcesAnySyntax(ActorSystem.getClass, "/" + defaultLocation, configParseOptions)))
  } catch { case _ ⇒ None }

  private def fromHome = try {
    Option(ConfigFactory.systemProperties.withFallback(
      ConfigFactory.parseFileAnySyntax(new File(ActorSystem.GlobalHome.get + "/config/" + defaultLocation), configParseOptions)))
  } catch { case _ ⇒ None }

  private def emptyConfig = ConfigFactory.systemProperties
}