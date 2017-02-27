/**
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */
package tutorial_3

import akka.actor.{ Actor, ActorLogging, Props }
import tutorial_3.Device.{ ReadTemperature, RecordTemperature, RespondTemperature, TemperatureRecorded }
import tutorial_3.DeviceManager.{ DeviceRegistered, RequestTrackDevice }

object Device {

  def props(groupId: String, deviceId: String): Props = Props(new Device(groupId, deviceId))

  case class RecordTemperature(requestId: Long, value: Double)
  case class TemperatureRecorded(requestId: Long)

  case class ReadTemperature(requestId: Long)
  case class RespondTemperature(requestId: Long, value: Option[Double])
}

class Device(groupId: String, deviceId: String) extends Actor with ActorLogging {
  var lastTemperatureReading: Option[Double] = None

  override def preStart(): Unit = log.info(s"Device actor $groupId-$deviceId started")

  override def postStop(): Unit = log.info(s"Device actor $groupId-$deviceId stopped")

  override def receive: Receive = {
    case RequestTrackDevice(`groupId`, `deviceId`) =>
      sender() ! DeviceRegistered

    case RequestTrackDevice(groupId, deviceId) =>
      log.warning(s"Ignoring TrackDevice request for $groupId-$deviceId. " +
        s"This actor is responsible for ${this.groupId}-${this.deviceId}.")

    case RecordTemperature(id, value) =>
      log.info(s"Recorded temperature reading $value with $id")
      lastTemperatureReading = Some(value)
      sender() ! TemperatureRecorded(id)

    case ReadTemperature(id) =>
      sender() ! RespondTemperature(id, lastTemperatureReading)
  }
}
