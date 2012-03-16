/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.amqp

import akka.AkkaException

class AkkaAMQPException(message: String, cause: Throwable = null) extends AkkaException(message, cause) {
  def this(message: String) = this(message, null)
}
