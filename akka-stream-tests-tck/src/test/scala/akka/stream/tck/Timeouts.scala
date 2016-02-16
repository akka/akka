/**
 * Copyright (C) 2014-2016 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.tck

import akka.actor.ActorSystem
import scala.concurrent.duration._
import akka.testkit._

/**
 * Specifies timeouts for the TCK
 */
object Timeouts {

  def publisherShutdownTimeoutMillis: Int = 3000

  def defaultTimeoutMillis: Int = 800

}
