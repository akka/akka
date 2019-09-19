/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor

import akka.annotation.DoNotInherit
import akka.annotation.InternalApi

object ClassicActorSystemProvider {
// FIXME Works, but I'm not sure if this is too magic (wide) since a Typed ActorSystem suddenly can be used everywhere
//       when a Classic is requested

  import scala.language.implicitConversions

  /**
   * Implicit conversion from [[ClassicActorSystemProvider]] to [[ActorSystem]]
   * Useful to be able to use a `akka.actor.typed.ActorSystem` when a `akka.actor.ActorSystem` is
   * requested, such as [[ExtensionId.apply]].
   */
  implicit def toClassicActorSystem(provider: ClassicActorSystemProvider): ActorSystem =
    provider.classicSystem
}

/**
 * Glue API introduced to allow minimal user effort integration between classic and typed for example for streams.
 *
 * Not for user extension.
 */
@DoNotInherit
trait ClassicActorSystemProvider {

  /** INTERNAL API */
  @InternalApi
  private[akka] def classicSystem: ActorSystem
}

/**
 * Glue API introduced to allow minimal user effort integration between classic and typed for example for streams.
 *
 * Not for user extension.
 */
@DoNotInherit
trait ClassicActorContextProvider {

  /** INTERNAL API */
  @InternalApi
  private[akka] def classicActorContext: ActorContext
}
