/**
 *  Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.osgi.test

import akka.osgi.ActorSystemActivator
import akka.actor.{ Props, ActorSystem }
import PingPong._
import org.osgi.framework.BundleContext

/**
 * A set of [[akka.osgi.ActorSystemActivator]]s for testing purposes
 */
object TestActivators {

  val ACTOR_SYSTEM_NAME_PATTERN = "actor-system-for-bundle-%s"

}

/**
 * Simple ActorSystemActivator that starts the sample ping-pong application
 */
class PingPongActorSystemActivator extends ActorSystemActivator {

  def configure(context: BundleContext, system: ActorSystem) {
    system.actorOf(Props(new PongActor), name = "pong")
    registerService(context, system)
  }

}

/**
 * [[akka.osgi.ActorSystemActivator]] implementation that determines [[akka.actor.ActorSystem]] name at runtime
 */
class RuntimeNameActorSystemActivator extends ActorSystemActivator {

  def configure(context: BundleContext, system: ActorSystem) = registerService(context, system);

  override def getActorSystemName(context: BundleContext) =
    TestActivators.ACTOR_SYSTEM_NAME_PATTERN.format(context.getBundle.getBundleId)

}