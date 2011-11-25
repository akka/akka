/**
 * Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.serialization

import akka.actor.{ ExtensionId, ExtensionIdProvider, ActorSystemImpl }

object SerializationExtension extends ExtensionId[Serialization] with ExtensionIdProvider {
  override def lookup = SerializationExtension
  override def createExtension(system: ActorSystemImpl): Serialization = new Serialization(system)
}