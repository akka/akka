package sample.zeromq.pubsub.app

import sample.zeromq.pubsub.actor.PublisherActor
import akka.actor.{ Props, ActorSystem }

object AkkaPublisherApp extends App {
  val system = ActorSystem("zmq")

  val requestActor = system.actorOf(Props[PublisherActor], name = "publisherActor")

}
