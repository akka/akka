package akka.osgi.test

import akka.osgi.ActorSystemActivator
import akka.actor.{ Props, ActorSystem }
import PingPong._
import org.osgi.framework.BundleContext

/**
 * Sample ActorSystemActivator implementation used for testing purposes
 */
class TestActorSystemActivator extends ActorSystemActivator {

  def configure(context: BundleContext, system: ActorSystem) {
    system.actorOf(Props(new PongActor), name = "pong")
    registerService(context, system)
  }

}
