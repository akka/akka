package sample.zeromq.reqrep.app

import akka.actor.{ Props, ActorSystem }
import sample.zeromq.reqrep.actor.RequestActor

object AkkaRequesterApp extends App {
  val system = ActorSystem("zmq")

  val requestActor = system.actorOf(Props[RequestActor], name = "requestActor")
}
