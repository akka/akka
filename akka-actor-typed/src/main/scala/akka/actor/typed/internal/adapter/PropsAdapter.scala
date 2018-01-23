/**
 * Copyright (C) 2017-2018 Lightbend Inc. <https://www.lightbend.com>
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

  def apply[T](behavior: () ⇒ Behavior[T], deploy: Props = Props.empty): akka.actor.Props = {

    val props = akka.actor.Props(new ActorAdapter(behavior()))

    (deploy.firstOrElse[DispatcherSelector](DispatcherDefault()) match {
      case _: DispatcherDefault          ⇒ props
      case DispatcherFromConfig(name, _) ⇒ props.withDispatcher(name)
    }).withDeploy(Deploy.local) // disallow remote deployment for typed actors
  }

}
