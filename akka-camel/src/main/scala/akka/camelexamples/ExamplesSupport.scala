/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.camelexamples

import akka.camel._
import akka.util.duration._
import akka.actor.{ Actor, OneForOneStrategy }
import akka.actor.SupervisorStrategy._

private[camelexamples] object ExamplesSupport {
  val retry3xWithin1s = OneForOneStrategy(maxNrOfRetries = 3, withinTimeRange = 1 second) {
    case _: Exception ⇒ Restart
  }
}

private[camelexamples] class SysOutConsumer extends Consumer {
  override def activationTimeout = 10 seconds
  def endpointUri = "file://data/input/CamelConsumer"

  protected def receive = {
    case msg: CamelMessage ⇒ {
      printf("Received '%s'\n", msg.bodyAs[String])
    }
  }
}

private[camelexamples] class TroubleMaker extends Consumer {
  def endpointUri = "WRONG URI"

  println("Trying to instantiate conumer with uri: " + endpointUri)
  protected def receive = { case _ ⇒ }
}

private[camelexamples] class SysOutActor(implicit camel: Camel) extends Actor {
  implicit val camelContext = camel.context
  protected def receive = {
    case msg: CamelMessage ⇒ {
      printf("Received '%s'\n", msg.bodyAs[String])
    }
  }
}