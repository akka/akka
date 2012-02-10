/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.config

import akka.AkkaException
import akka.actor.ActorSystem

class ConfigurationException(message: String, cause: Throwable = null, system: ActorSystem) extends AkkaException(message, cause, system) {
  def this(msg: String, system: ActorSystem) = this(msg, null, system);
}

class ModuleNotAvailableException(message: String, cause: Throwable = null, system: ActorSystem) extends AkkaException(message, cause, system: ActorSystem) {
  def this(msg: String, system: ActorSystem) = this(msg, null, system);
}
