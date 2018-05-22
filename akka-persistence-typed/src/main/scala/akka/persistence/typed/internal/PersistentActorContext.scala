/*
 * Copyright (C) 2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.typed.internal

import akka.actor.typed.ActorContext
import akka.annotation.InternalApi

@InternalApi
private[typed] trait PersistentActorContext[T] extends ActorContext[T] {

}
