/**
 * Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.serialization

import akka.actor.{ ActorSystem, ExtensionId, ExtensionIdProvider, ActorSystemImpl }

object SerializationExtension extends ExtensionId[Serialization] with ExtensionIdProvider {
  override def get(system: ActorSystem): Serialization = super.get(system)
  override def lookup = SerializationExtension
  override def createExtension(system: ActorSystemImpl): Serialization = new Serialization(system)
}