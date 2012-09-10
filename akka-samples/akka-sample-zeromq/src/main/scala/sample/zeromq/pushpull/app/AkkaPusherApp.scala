package sample.zeromq.pushpull.app

import akka.actor.{ Props, ActorSystem }
import sample.zeromq.pushpull.actor.PusherActor

object AkkaPusherApp extends App {
  val system = ActorSystem("zmq")

  val requestActor = system.actorOf(Props[PusherActor], name = "pusherActor")
}
