/**
 * Copyright (C) 2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.typed.scaladsl.adapter

import akka.typed.Behavior
import akka.typed.EmptyProps
import akka.typed.Props
import akka.typed.internal.adapter.ActorAdapter

/**
 * Wrap [[akka.typed.Behavior]] in an untyped [[akka.actor.Props]], i.e. when
 * spawning a typed child actor from an untyped parent actor.
 * This is normally not needed because you can use the extension methods
 * `spawn` and `spawnAnonymous` on an untyped `ActorContext`, but it's needed
 * when using typed actors with an existing library/tool that provides an API that
 * takes an untyped [[akka.actor.Props]] parameter. Cluster Sharding is an
 * example of that.
 */
object PropsAdapter {
  def apply[T](behavior: ⇒ Behavior[T], deploy: Props = Props.empty): akka.actor.Props =
    akka.typed.internal.adapter.PropsAdapter(() ⇒ behavior, deploy)
}
