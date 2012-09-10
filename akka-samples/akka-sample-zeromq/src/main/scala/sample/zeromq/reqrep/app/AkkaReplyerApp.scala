package sample.zeromq.reqrep.app

import akka.actor.{ ActorSystem, Props }
import akka.zeromq.{ Listener, Bind, ZeroMQExtension }
import sample.zeromq.reqrep.actor.ReplyActor

object AkkaReplyerApp extends App {
  val system = ActorSystem("zmq")

  val serverSocket = ZeroMQExtension(system).newRepSocket(
    Array(
      Bind("tcp://127.0.0.1:1234"),
      Listener(system.actorOf(Props[ReplyActor]))))
}