package sample.zeromq.pubsub.app

import akka.actor.{ ActorSystem, Props }
import akka.zeromq.{ Subscribe, Connect, Listener, ZeroMQExtension }
import sample.zeromq.pubsub.actor.SubscriberActor

object AkkaSubscriberApp extends App {
  val system = ActorSystem("zmq")

  val serverSocket = ZeroMQExtension(system).newSubSocket(
    Array(
      Connect("tcp://127.0.0.1:1234"),
      Subscribe(""),
      Listener(system.actorOf(Props[SubscriberActor]))))
}