/**
 * Copyright (C) 2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.actor.typed
package internal
package adapter

import akka.actor.typed.Behavior
import akka.actor.typed.EmptyProps
import akka.actor.typed.Props
import akka.annotation.InternalApi

/**
 * INTERNAL API
 */
@InternalApi private[akka] object PropsAdapter {
  def apply[T](behavior: () ⇒ Behavior[T], deploy: Props = Props.empty): akka.actor.Props = {
    // FIXME use Props, e.g. dispatcher
    akka.actor.Props(new ActorAdapter(behavior()))
  }

}
