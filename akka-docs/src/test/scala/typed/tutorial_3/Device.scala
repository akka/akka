/*
 * Copyright (C) 2009-2023 Lightbend Inc. <https://www.lightbend.com>
 */

package typed.tutorial_3

//#full-device
import akka.actor.typed.ActorRef
import akka.actor.typed.Behavior
import akka.actor.typed.PostStop
import akka.actor.typed.Signal
import akka.actor.typed.scaladsl.AbstractBehavior
import akka.actor.typed.scaladsl.ActorContext
import akka.actor.typed.scaladsl.Behaviors
import akka.actor.typed.scaladsl.LoggerOps

object Device {
  def apply(groupId: String, deviceId: String): Behavior[Command] =
    Behaviors.setup(context => new Device(context, groupId, deviceId, None))

  def apply(groupId: String, deviceId: String, temperature: Double): Behavior[Command] =
    Behaviors.setup(context => new Device(context, groupId, deviceId, Some(temperature)))

  sealed trait Command

  final case class ReadTemperature(requestId: Long, replyTo: ActorRef[RespondTemperature]) extends Command
  final case class RespondTemperature(requestId: Long, value: Option[Double])

  //#write-protocol
  final case class RecordTemperature(requestId: Long, value: Double, replyTo: ActorRef[TemperatureRecorded])
      extends Command
  final case class TemperatureRecorded(requestId: Long)
  //#write-protocol
}

class Device(
    context: ActorContext[Device.Command],
    groupId: String,
    deviceId: String,
    lastTemperatureReading: Option[Double])
    extends AbstractBehavior[Device.Command](context) {
  import Device._

  context.log.info2("Device actor {}-{} started", groupId, deviceId)

  private def updatedDevice(temperature: Double): Behavior[Command] = {
    Device(groupId, deviceId, temperature)
  }

  override def onMessage(msg: Command): Behavior[Command] = {
    msg match {
      case RecordTemperature(id, value, replyTo) =>
        context.log.info2("Recorded temperature reading {} with {}", value, id)
        replyTo ! TemperatureRecorded(id)
        updatedDevice(value)

      case ReadTemperature(id, replyTo) =>
        replyTo ! RespondTemperature(id, lastTemperatureReading)
        this
    }
  }

  override def onSignal: PartialFunction[Signal, Behavior[Command]] = {
    case PostStop =>
      context.log.info2("Device actor {}-{} stopped", groupId, deviceId)
      this
  }

}
//#full-device
