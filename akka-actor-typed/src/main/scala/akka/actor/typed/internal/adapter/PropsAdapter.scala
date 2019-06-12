/*
 * Copyright (C) 2017-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor.typed.internal.adapter

import akka.actor.Deploy
import akka.actor.typed.Behavior
import akka.actor.typed.DispatcherSelector
import akka.actor.typed.Props
import akka.actor.typed.internal.PropsImpl._
import akka.annotation.InternalApi

/**
 * INTERNAL API
 */
@InternalApi private[akka] object PropsAdapter {
  def apply[T](
      behavior: () => Behavior[T],
      deploy: Props = Props.empty,
      rethrowTypedFailure: Boolean = true): akka.actor.Props = {
    val props = akka.actor.Props(new ActorAdapter(behavior(), rethrowTypedFailure))

    (deploy.firstOrElse[DispatcherSelector](DispatcherDefault()) match {
      case _: DispatcherDefault          => props
      case DispatcherFromConfig(name, _) => props.withDispatcher(name)
      case _: DispatcherSameAsParent     => props.withDispatcher(Deploy.DispatcherSameAsParent)
    }).withDeploy(Deploy.local) // disallow remote deployment for typed actors
  }

}
