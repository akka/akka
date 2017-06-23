/**
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */
package tutorial_4

import akka.actor.{ Actor, ActorLogging, ActorRef, Props, Terminated }

import scala.concurrent.duration._

//#query-full
//#query-outline
object DeviceGroupQuery {
  case object CollectionTimeout

  def props(
    actorToDeviceId: Map[ActorRef, String],
    requestId:       Long,
    requester:       ActorRef,
    timeout:         FiniteDuration
  ): Props = {
    Props(new DeviceGroupQuery(actorToDeviceId, requestId, requester, timeout))
  }
}

class DeviceGroupQuery(
  actorToDeviceId: Map[ActorRef, String],
  requestId:       Long,
  requester:       ActorRef,
  timeout:         FiniteDuration
) extends Actor with ActorLogging {
  import DeviceGroupQuery._
  import context.dispatcher
  val queryTimeoutTimer = context.system.scheduler.scheduleOnce(timeout, self, CollectionTimeout)

  override def preStart(): Unit = {
    actorToDeviceId.keysIterator.foreach { deviceActor =>
      context.watch(deviceActor)
      deviceActor ! Device.ReadTemperature(0)
    }
  }

  override def postStop(): Unit = {
    queryTimeoutTimer.cancel()
  }

  //#query-outline
  //#query-state
  override def receive: Receive =
    waitingForReplies(
      Map.empty,
      actorToDeviceId.keySet
    )

  def waitingForReplies(
    repliesSoFar: Map[String, DeviceGroup.TemperatureReading],
    stillWaiting: Set[ActorRef]
  ): Receive = {
    case Device.RespondTemperature(0, valueOption) =>
      val deviceActor = sender()
      val reading = valueOption match {
        case Some(value) => DeviceGroup.Temperature(value)
        case None        => DeviceGroup.TemperatureNotAvailable
      }
      receivedResponse(deviceActor, reading, stillWaiting, repliesSoFar)

    case Terminated(deviceActor) =>
      receivedResponse(deviceActor, DeviceGroup.DeviceNotAvailable, stillWaiting, repliesSoFar)

    case CollectionTimeout =>
      val timedOutReplies =
        stillWaiting.map { deviceActor =>
          val deviceId = actorToDeviceId(deviceActor)
          deviceId -> DeviceGroup.DeviceTimedOut
        }
      requester ! DeviceGroup.RespondAllTemperatures(requestId, repliesSoFar ++ timedOutReplies)
      context.stop(self)
  }
  //#query-state

  //#query-collect-reply
  def receivedResponse(
    deviceActor:  ActorRef,
    reading:      DeviceGroup.TemperatureReading,
    stillWaiting: Set[ActorRef],
    repliesSoFar: Map[String, DeviceGroup.TemperatureReading]
  ): Unit = {
    context.unwatch(deviceActor)
    val deviceId = actorToDeviceId(deviceActor)
    val newStillWaiting = stillWaiting - deviceActor

    val newRepliesSoFar = repliesSoFar + (deviceId -> reading)
    if (newStillWaiting.isEmpty) {
      requester ! DeviceGroup.RespondAllTemperatures(requestId, newRepliesSoFar)
      context.stop(self)
    } else {
      context.become(waitingForReplies(newRepliesSoFar, newStillWaiting))
    }
  }
  //#query-collect-reply

  //#query-outline
}
//#query-outline
//#query-full
