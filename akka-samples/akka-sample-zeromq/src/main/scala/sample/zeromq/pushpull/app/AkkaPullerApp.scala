package sample.zeromq.pushpull.app

import akka.actor.{ ActorSystem, Props }
import akka.zeromq.{ Subscribe, Connect, Listener, ZeroMQExtension }
import sample.zeromq.pushpull.actor.PullerActor

object AkkaPullerApp extends App {
  val system = ActorSystem("zmq")

  val serverSocket = ZeroMQExtension(system).newSubSocket(
    Array(
      Connect("tcp://127.0.0.1:1234"),
      Subscribe(""),
      Listener(system.actorOf(Props[PullerActor]))))
}