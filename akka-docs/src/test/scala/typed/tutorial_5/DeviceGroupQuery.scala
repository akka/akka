/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package typed.tutorial_5

import scala.concurrent.duration._

import akka.actor.typed.ActorRef
import akka.actor.typed.Behavior
import akka.actor.typed.scaladsl.AbstractBehavior
import akka.actor.typed.scaladsl.ActorContext
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.scaladsl.TimerScheduler
import typed.tutorial_5.Device.DeviceMessage
import typed.tutorial_5.Device.ReadTemperature
import typed.tutorial_5.Device.RespondTemperature
import typed.tutorial_5.DeviceManager.DeviceNotAvailable
import typed.tutorial_5.DeviceManager.DeviceTimedOut
import typed.tutorial_5.DeviceManager.RespondAllTemperatures
import typed.tutorial_5.DeviceManager.Temperature
import typed.tutorial_5.DeviceManager.TemperatureNotAvailable
import typed.tutorial_5.DeviceManager.TemperatureReading

//#query-full
//#query-outline
object DeviceGroupQuery {

  def apply(
      deviceIdToActor: Map[String, ActorRef[Device.DeviceMessage]],
      requestId: Long,
      requester: ActorRef[RespondAllTemperatures],
      timeout: FiniteDuration): Behavior[DeviceGroupQueryMessage] = {
    Behaviors.setup { context =>
      Behaviors.withTimers { timers =>
        new DeviceGroupQuery(deviceIdToActor, requestId, requester, timeout, context, timers)
      }
    }
  }

  trait DeviceGroupQueryMessage

  private case object CollectionTimeout extends DeviceGroupQueryMessage

  final case class WrappedRespondTemperature(response: RespondTemperature) extends DeviceGroupQueryMessage

  private final case class DeviceTerminated(deviceId: String) extends DeviceGroupQueryMessage
}

class DeviceGroupQuery(
    deviceIdToActor: Map[String, ActorRef[DeviceMessage]],
    requestId: Long,
    requester: ActorRef[RespondAllTemperatures],
    timeout: FiniteDuration,
    context: ActorContext[DeviceGroupQuery.DeviceGroupQueryMessage],
    timers: TimerScheduler[DeviceGroupQuery.DeviceGroupQueryMessage])
    extends AbstractBehavior[DeviceGroupQuery.DeviceGroupQueryMessage] {

  import DeviceGroupQuery._
  timers.startSingleTimer(CollectionTimeout, CollectionTimeout, timeout)

  private val respondTemperatureAdapter = context.messageAdapter(WrappedRespondTemperature.apply)

  //#query-outline
  //#query-state
  private var repliesSoFar = Map.empty[String, TemperatureReading]
  private var stillWaiting = deviceIdToActor.keySet

  //#query-state
  //#query-outline

  deviceIdToActor.foreach {
    case (deviceId, device) =>
      context.watchWith(device, DeviceTerminated(deviceId))
      device ! ReadTemperature(0, respondTemperatureAdapter)
  }

  //#query-outline
  //#query-state
  override def onMessage(msg: DeviceGroupQueryMessage): Behavior[DeviceGroupQueryMessage] =
    msg match {
      case WrappedRespondTemperature(response) => onRespondTemperature(response)
      case DeviceTerminated(deviceId)          => onDeviceTerminated(deviceId)
      case CollectionTimeout                   => onCollectionTimout()
    }

  private def onRespondTemperature(response: RespondTemperature): Behavior[DeviceGroupQueryMessage] = {
    val reading = response.value match {
      case Some(value) => Temperature(value)
      case None        => TemperatureNotAvailable
    }

    val deviceId = response.deviceId
    repliesSoFar += (deviceId -> reading)
    stillWaiting -= deviceId

    respondWhenAllCollected()
  }

  private def onDeviceTerminated(deviceId: String): Behavior[DeviceGroupQueryMessage] = {
    if (stillWaiting(deviceId)) {
      repliesSoFar += (deviceId -> DeviceNotAvailable)
      stillWaiting -= deviceId
    }
    respondWhenAllCollected()
  }

  private def onCollectionTimout(): Behavior[DeviceGroupQueryMessage] = {
    repliesSoFar ++= stillWaiting.map(deviceId => deviceId -> DeviceTimedOut)
    stillWaiting = Set.empty
    respondWhenAllCollected()
  }
  //#query-state

  //#query-collect-reply
  private def respondWhenAllCollected(): Behavior[DeviceGroupQueryMessage] = {
    if (stillWaiting.isEmpty) {
      requester ! RespondAllTemperatures(requestId, repliesSoFar)
      Behaviors.stopped
    } else {
      this
    }
  }
  //#query-collect-reply
  //#query-outline
}
//#query-outline
//#query-full
