/**
 * Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.config
import com.typesafe.config.Config

object ConfigImplicits {
  implicit def decorateConfig(config: Config) = new ConfigWrapper(config)
}

class ConfigWrapper(config: Config) {

  def getStringOption(path: String): Option[String] = {
    config.hasPath(path) match {
      case false ⇒ None
      case true  ⇒ Some(config.getString(path))
    }
  }

  def getConfigOption(path: String): Option[Config] = {
    config.hasPath(path) match {
      case false ⇒ None
      case true  ⇒ Some(config.getConfig(path))
    }
  }

}