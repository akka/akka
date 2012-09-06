package sample.zeromq.reqrep

import akka.actor.{ ActorSystem, Actor, Props }
import akka.zeromq.{ Listener, Bind, ZeroMQExtension }

object ReplyerApp extends App {
  val system = ActorSystem("zmq")
  val serverSocket = ZeroMQExtension(system).newRepSocket(
    Array(
      Bind("tcp://127.0.0.1:1234"),
      Listener(system.actorOf(Props[ReplyActor]))))
}