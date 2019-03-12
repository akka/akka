/*
 * Copyright (C) 2018-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package typed.tutorial_3

import akka.actor.typed.ActorRef
import akka.actor.typed.PostStop
import akka.actor.typed.Signal

object DeviceInProgress1 {

  //#read-protocol-1
  import akka.actor.typed.ActorRef

  object Device {
    sealed trait DeviceMessage
    final case class ReadTemperature(replyTo: ActorRef[RespondTemperature]) extends DeviceMessage
    final case class RespondTemperature(value: Option[Double])
  }
  //#read-protocol-1

}

object DeviceInProgress2 {
  import akka.actor.typed.ActorRef

  //#device-with-read
  import akka.actor.typed.Behavior
  import akka.actor.typed.scaladsl.AbstractBehavior
  import akka.actor.typed.scaladsl.ActorContext
  import akka.actor.typed.scaladsl.Behaviors

  object Device {
    def apply(groupId: String, deviceId: String): Behavior[DeviceMessage] =
      Behaviors.setup(context => new Device(context, groupId, deviceId))

    //#read-protocol-2
    sealed trait DeviceMessage
    final case class ReadTemperature(requestId: Long, replyTo: ActorRef[RespondTemperature]) extends DeviceMessage
    final case class RespondTemperature(requestId: Long, value: Option[Double])
    //#read-protocol-2
  }

  class Device(context: ActorContext[Device.DeviceMessage], groupId: String, deviceId: String)
      extends AbstractBehavior[Device.DeviceMessage] {
    import Device._

    var lastTemperatureReading: Option[Double] = None

    context.log.info("Device actor {}-{} started", groupId, deviceId)

    override def onMessage(msg: DeviceMessage): Behavior[DeviceMessage] = {
      msg match {
        case ReadTemperature(id, replyTo) =>
          replyTo ! RespondTemperature(id, lastTemperatureReading)
          this
      }
    }

    override def onSignal: PartialFunction[Signal, Behavior[DeviceMessage]] = {
      case PostStop =>
        context.log.info("Device actor {}-{} stopped", groupId, deviceId)
        this
    }

  }
  //#device-with-read

}

object DeviceInProgress3 {

  object Device {
    //#write-protocol-1
    sealed trait DeviceMessage
    final case class RecordTemperature(value: Double) extends DeviceMessage
    //#write-protocol-1
  }
}
