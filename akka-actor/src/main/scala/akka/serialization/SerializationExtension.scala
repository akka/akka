/**
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.serialization

import akka.actor.{ ActorSystem, ExtensionId, ExtensionIdProvider, ExtendedActorSystem }

/**
 * SerializationExtension is an Akka Extension to interact with the Serialization
 * that is built into Akka
 */
object SerializationExtension extends ExtensionId[Serialization] with ExtensionIdProvider {
  override def get(system: ActorSystem): Serialization = super.get(system)
  override def lookup = SerializationExtension
  override def createExtension(system: ExtendedActorSystem): Serialization = new Serialization(system)
}
