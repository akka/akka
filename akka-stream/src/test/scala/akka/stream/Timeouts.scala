/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream

import akka.actor.ActorSystem
import scala.concurrent.duration._
import akka.testkit._

/**
 * Specifies timeouts for the TCK
 */
object Timeouts {

  def publisherShutdownTimeoutMillis: Int = 1000

  def defaultTimeoutMillis(implicit system: ActorSystem): Int =
    500.millis.dilated(system).toMillis.toInt

}
