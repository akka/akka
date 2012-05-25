/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.config

import akka.AkkaException

@deprecated("Will be moved to akka.ConfigurationException in Akka 2.1", "2.0.2")
class ConfigurationException(message: String, cause: Throwable = null) extends AkkaException(message, cause) {
  def this(msg: String) = this(msg, null);
}

@deprecated("Will be removed in Akka 2.1, no replacement", "2.0.2")
class ModuleNotAvailableException(message: String, cause: Throwable = null) extends AkkaException(message, cause) {
  def this(msg: String) = this(msg, null);
}
