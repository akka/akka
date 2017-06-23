/**
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */
package tutorial_6

import akka.actor.{ Actor, ActorLogging, ActorRef, Props }

object IotSupervisor {

  def props(): Props = Props(new IotSupervisor)

}

class IotSupervisor extends Actor with ActorLogging {
  val deviceManager: ActorRef = context.system.actorOf(DeviceManager.props(), "device-manager")

  override def preStart(): Unit = log.info("IoT Application started")

  override def postStop(): Unit = log.info("IoT Application stopped")

  // No need to handle any messages
  override def receive = Actor.emptyBehavior

}
