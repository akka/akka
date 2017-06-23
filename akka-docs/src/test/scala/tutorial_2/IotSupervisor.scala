/**
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */
package tutorial_2

//#iot-supervisor
import akka.actor.{ Actor, ActorLogging, Props }

object IotSupervisor {
  def props(): Props = Props(new IotSupervisor)
}

class IotSupervisor extends Actor with ActorLogging {
  override def preStart(): Unit = log.info("IoT Application started")
  override def postStop(): Unit = log.info("IoT Application stopped")

  // No need to handle any messages
  override def receive = Actor.emptyBehavior

}
//#iot-supervisor
