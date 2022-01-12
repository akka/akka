/*
 * Copyright (C) 2019-2022 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor.typed.internal.adapter

import akka.ConfigurationException
import akka.actor.typed._
import akka.annotation.InternalApi
import akka.util.ErrorMessages

/**
 * INTERNAL API
 */
@InternalApi private[typed] object ActorRefFactoryAdapter {

  private val remoteDeploymentNotAllowed = "Remote deployment not allowed for typed actors"
  def spawnAnonymous[T](
      context: akka.actor.ActorRefFactory,
      behavior: Behavior[T],
      props: Props,
      rethrowTypedFailure: Boolean): ActorRef[T] = {
    try {
      ActorRefAdapter(context.actorOf(internal.adapter.PropsAdapter(() => behavior, props, rethrowTypedFailure)))
    } catch {
      case ex: ConfigurationException if ex.getMessage.startsWith(ErrorMessages.RemoteDeploymentConfigErrorPrefix) =>
        throw new ConfigurationException(remoteDeploymentNotAllowed, ex)
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
      case ex: ConfigurationException if ex.getMessage.startsWith(ErrorMessages.RemoteDeploymentConfigErrorPrefix) =>
        throw new ConfigurationException(remoteDeploymentNotAllowed, ex)
    }
  }
}
