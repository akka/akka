/*
 * Copyright (C) 2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor.typed.internal.adapter
import akka.actor.typed._
import akka.annotation.InternalApi
import akka.ConfigurationException

/**
 * INTERNAL API
 */
@InternalApi private[typed] object ActorRefFactoryAdapter {
  def spawnAnonymous[T](context: akka.actor.ActorRefFactory, behavior: Behavior[T], props: Props, rethrowTypedFailure: Boolean): ActorRef[T] = {
    try {
      ActorRefAdapter(
        context.actorOf(internal.adapter.PropsAdapter(() => behavior, props, rethrowTypedFailure)))
    } catch {
      case ex: ConfigurationException if ex.getMessage.startsWith("configuration requested remote deployment") =>
        throw new ConfigurationException("Remote deployment not allowed for typed actors", ex)
    }
  }

  def spawn[T](
      actorRefFactory: akka.actor.ActorRefFactory,
      behavior: Behavior[T],
      name: String,
      props: Props,
      rethrowTypedFailure: Boolean): ActorRef[T] = {
    try {
      ActorRefAdapter(
        actorRefFactory.actorOf(
          internal.adapter.PropsAdapter(() => Behavior.validateAsInitial(behavior), props, rethrowTypedFailure),
          name))
    } catch {
      case ex: ConfigurationException if ex.getMessage.startsWith("configuration requested remote deployment") =>
        throw new ConfigurationException("Remote deployment not allowed for typed actors", ex)
    }
  }

}
