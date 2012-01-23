/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.serialization

import akka.actor.{ ActorSystem, ExtensionId, ExtensionIdProvider, ActorSystemImpl }

/**
 * SerializationExtension is an Akka Extension to interact with the Serialization
 * that is built into Akka
 */
object SerializationExtension extends ExtensionId[Serialization] with ExtensionIdProvider {
  override def get(system: ActorSystem): Serialization = super.get(system)
  override def lookup = SerializationExtension
  override def createExtension(system: ActorSystemImpl): Serialization = new Serialization(system)
}