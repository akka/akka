/**
 * Copyright (C) 2017-2018 Lightbend Inc. <https://www.lightbend.com>
 */
package akka.actor.typed
package internal
package adapter

import akka.actor.typed.Behavior
import akka.actor.typed.EmptyProps
import akka.actor.typed.Props
import akka.annotation.InternalApi
import akka.dispatch.ExecutionContexts

/**
 * INTERNAL API
 */
@InternalApi private[akka] object PropsAdapter {
  def apply[T](behavior: () ⇒ Behavior[T], deploy: Props = Props.empty): akka.actor.Props = {
    // FIXME use Props, e.g. dispatcher

    val props = akka.actor.Props(new ActorAdapter(behavior()))

    deploy.firstOrElse[DispatcherSelector](DispatcherDefault()) match {
      case _: DispatcherDefault          ⇒ props
      case DispatcherFromConfig(name, _) ⇒ props.withDispatcher(name)
    }
  }

}
