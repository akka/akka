package tutorial_3

object DeviceInProgress1 {

  object Device {
    //#read-protocol-1
    final case object ReadTemperature
    final case class RespondTemperature(value: Option[Double])
    //#read-protocol-1
  }

}

object DeviceInProgress2 {

  //#device-with-read
  import akka.actor.{ Actor, ActorLogging, Props }

  object Device {
    def props(groupId: String, deviceId: String): Props = Props(new Device(groupId, deviceId))

    //#read-protocol-2
    final case class ReadTemperature(requestId: Long)
    final case class RespondTemperature(requestId: Long, value: Option[Double])
    //#read-protocol-2
  }

  class Device(groupId: String, deviceId: String) extends Actor with ActorLogging {
    import Device._

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

  object Device {
    //#write-protocol-1
    final case class RecordTemperature(value: Double)
    //#write-protocol-1
  }
}
