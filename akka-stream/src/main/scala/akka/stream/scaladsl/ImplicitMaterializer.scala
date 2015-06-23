/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.scaladsl

import akka.actor.Actor
import akka.stream.ActorMaterializerSettings
import akka.stream.ActorMaterializer

/**
 * Mix this trait into your [[akka.actor.Actor]] if you need an implicit
 * [[akka.stream.Materializer]] in scope.
 *
 * Subclass may override [[#materializerSettings]] to define custom
 * [[akka.stream.ActorMaterializerSettings]] for the `Materializer`.
 */
trait ImplicitMaterializer { this: Actor ⇒

  /**
   * Subclass may override this to define custom
   * [[akka.stream.ActorMaterializerSettings]] for the `Materializer`.
   */
  def materializerSettings: ActorMaterializerSettings = ActorMaterializerSettings(context.system)

  final implicit val materializer: ActorMaterializer = ActorMaterializer(Some(materializerSettings))
}
