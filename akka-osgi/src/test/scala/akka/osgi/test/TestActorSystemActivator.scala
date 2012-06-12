package akka.osgi.test

import akka.osgi.ActorSystemActivator
import akka.actor.{ Props, ActorSystem }
import PingPong._

/**
 * Sample ActorSystemActivator implementation used for testing purposes
 */
class TestActorSystemActivator extends ActorSystemActivator {

  def configure(system: ActorSystem) {
    system.actorOf(Props(new PongActor), name = "pong")
  }

}
