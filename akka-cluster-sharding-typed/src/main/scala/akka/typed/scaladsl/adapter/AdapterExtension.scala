/**
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.typed.scaladsl.adapter

import akka.actor.ExtendedActorSystem
import akka.annotation.InternalApi
import akka.typed.internal.adapter.ActorSystemAdapter

/**
 * Internal API
 *
 * To not create a new adapter for every `toTyped` call we create one instance and keep in an extension
 */
@InternalApi private[akka] class AdapterExtension(sys: akka.actor.ActorSystem) extends akka.actor.Extension {
  val adapter = ActorSystemAdapter(sys)
}
/**
 * Internal API
 */
@InternalApi object AdapterExtension extends akka.actor.ExtensionId[AdapterExtension] {
  def createExtension(sys: ExtendedActorSystem): AdapterExtension = new AdapterExtension(sys)
}
