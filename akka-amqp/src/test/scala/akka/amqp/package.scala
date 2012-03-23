package akka

import actor.ActorSystem
import akka.util.duration._

package object amqp {
  val defaultConnectionProperties = ConnectionProperties(
    user = "guest",
    pass = "guest",
    addresses = Seq("localhost:5672"),
    vhost = "/",
    amqpHeartbeat = (30 seconds),
    maxReconnectDelay = (1 minutes),
    channelThreads = 5,
    system = ActorSystem("amqp"))

}