/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.camelexamples

import akka.camel._
import akka.util.duration._
import akka.actor.{ Actor, OneForOneStrategy }
import akka.actor.SupervisorStrategy._

object ExamplesSupport {
  val retry3xWithin1s = OneForOneStrategy(maxNrOfRetries = 3, withinTimeRange = 1 second) {
    case _: Exception ⇒ Restart
  }
}

class SysOutConsumer extends Consumer {
  override def activationTimeout = 10 seconds
  def endpointUri = "file://data/input/CamelConsumer"

  protected def receive = {
    case msg: Message ⇒ {
      printf("Received '%s'\n", msg.bodyAs[String])
    }
  }
}

class TroubleMaker extends Consumer {
  def endpointUri = "WRONG URI"

  println("Trying to instantiate conumer with uri: " + endpointUri)
  protected def receive = { case _ ⇒ }
}

class SysOutActor(implicit camel: Camel) extends Actor {
  protected def receive = {
    case msg: Message ⇒ {
      printf("Received '%s'\n", msg.bodyAs[String])
    }
  }
}