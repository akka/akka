/**
 * Copyright (C) 2009-2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor.typed.internal

import akka.annotation.InternalApi

import scala.util.Try

/**
 * INTERNAL API
 *
 * Message wrapper used to allow ActorContext.ask to map the response inside the asking actor.
 */
@InternalApi
private[akka] final class AskResponse[U, T](result: Try[U], adapter: Try[U] ⇒ T) {
  def adapt(): T = adapter(result)
}
