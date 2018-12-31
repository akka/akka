/*
 * Copyright (C) 2017-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor.typed
package internal
package adapter

import akka.actor.Deploy
import akka.annotation.InternalApi

/**
 * INTERNAL API
 */
@InternalApi private[akka] object PropsAdapter {
  def apply[T](behavior: () ⇒ Behavior[T], deploy: Props = Props.empty, isGuardian: Boolean = false): akka.actor.Props = {
    val props =
      if (isGuardian)
        akka.actor.Props(new GuardianActorAdapter(behavior()))
      else
        akka.actor.Props(new ActorAdapter(behavior()))

    (deploy.firstOrElse[DispatcherSelector](DispatcherDefault()) match {
      case _: DispatcherDefault          ⇒ props
      case DispatcherFromConfig(name, _) ⇒ props.withDispatcher(name)
    }).withDeploy(Deploy.local) // disallow remote deployment for typed actors
  }

}
