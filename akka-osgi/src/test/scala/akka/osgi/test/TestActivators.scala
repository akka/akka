/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.osgi.test

import akka.osgi.ActorSystemActivator
import akka.actor.{ ActorSystem, Props }
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

  def configure(context: BundleContext, system: ActorSystem): Unit = {
    system.actorOf(Props[PongActor], name = "pong")
    registerService(context, system)
  }

}

/**
 * [[akka.osgi.ActorSystemActivator]] implementation that determines [[akka.actor.ActorSystem]] name at runtime
 */
class RuntimeNameActorSystemActivator extends ActorSystemActivator {

  def configure(context: BundleContext, system: ActorSystem) = registerService(context, system)

  override def getActorSystemName(context: BundleContext) =
    TestActivators.ACTOR_SYSTEM_NAME_PATTERN.format(context.getBundle.getBundleId)

}
