/*
 * Copyright (C) 2009-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.osgi.test

import PingPong._
import org.osgi.framework.BundleContext

import akka.actor.{ ActorSystem, Props }
import akka.osgi.ActorSystemActivator

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
    system.actorOf(Props[PongActor](), name = "pong")
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
