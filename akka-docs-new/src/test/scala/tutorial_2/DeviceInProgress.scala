package tutorial_2

import tutorial_3.Device.{ReadTemperature, RecordTemperature, RespondTemperature, TemperatureRecorded}

object DeviceInProgress1 {

  //#read-protocol-1
  object Device {
    final case object ReadTemperature
    final case class RespondTemperature(value: Option[Double])
  }
  //#read-protocol-1

}

object DeviceInProgress2 {

  //#read-protocol-2
  object Device {

    final case class ReadTemperature(requestId: Long)

    final case class RespondTemperature(requestId: Long, value: Option[Double])

  }

  //#read-protocol-2

  //#device-with-read
  import akka.actor.{Actor, ActorLogging}

  class Device(groupId: String, deviceId: String) extends Actor with ActorLogging {
    var lastTemperatureReading: Option[Double] = None

    override def preStart(): Unit = log.info("Device actor {}-{} started", groupId, deviceId)

    override def postStop(): Unit = log.info("Device actor {}-{} stopped", groupId, deviceId)

    override def receive: Receive = {
      case ReadTemperature(id) =>
        sender() ! RespondTemperature(id, lastTemperatureReading)
    }

  }

  //#device-with-read

}

object DeviceInProgress3 {

  //#write-protocol-1
  object Device {
    final case class ReadTemperature(requestId: Long)
    final case class RespondTemperature(requestId: Long, value: Option[Double])

    final case class RecordTemperature(value: Double)
  }
  //#write-protocol-1
}