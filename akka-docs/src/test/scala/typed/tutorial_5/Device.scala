/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package typed.tutorial_5

//#device-with-passivate
import akka.actor.typed.ActorRef
import akka.actor.typed.Behavior
import akka.actor.typed.PostStop
import akka.actor.typed.Signal
import akka.actor.typed.scaladsl.AbstractBehavior
import akka.actor.typed.scaladsl.ActorContext
import akka.actor.typed.scaladsl.Behaviors

object Device {
  def apply(groupId: String, deviceId: String): Behavior[DeviceMessage] =
    Behaviors.setup(context ⇒ new Device(context, groupId, deviceId))

  sealed trait DeviceMessage

  final case class ReadTemperature(requestId: Long, replyTo: ActorRef[RespondTemperature])
    extends DeviceMessage
  final case class RespondTemperature(requestId: Long, deviceId: String, value: Option[Double])

  final case class RecordTemperature(requestId: Long, value: Double, replyTo: ActorRef[TemperatureRecorded])
    extends DeviceMessage
  final case class TemperatureRecorded(requestId: Long)

  case object Passivate extends DeviceMessage
}

class Device(context: ActorContext[Device.DeviceMessage], groupId: String, deviceId: String)
  extends AbstractBehavior[Device.DeviceMessage] {
  import Device._

  var lastTemperatureReading: Option[Double] = None

  context.log.info("Device actor {}-{} started", groupId, deviceId)

  override def onMessage(msg: DeviceMessage): Behavior[DeviceMessage] = {
    msg match {
      case RecordTemperature(id, value, replyTo) ⇒
        context.log.info("Recorded temperature reading {} with {}", value, id)
        lastTemperatureReading = Some(value)
        replyTo ! TemperatureRecorded(id)
        this

      case ReadTemperature(id, replyTo) ⇒
        replyTo ! RespondTemperature(id, deviceId, lastTemperatureReading)
        this

      case Passivate ⇒
        Behaviors.stopped
    }
  }

  override def onSignal: PartialFunction[Signal, Behavior[DeviceMessage]] = {
    case PostStop ⇒
      context.log.info("Device actor {}-{} stopped", groupId, deviceId)
      this
  }

}
//#device-with-passivate
