/**
 * Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.serialization
import akka.actor.{ ExtensionProvider, Extension, ActorSystemImpl }

object SerializationExtension extends Extension[Serialization] with ExtensionProvider {
  override def lookup = SerializationExtension
  override def createExtension(system: ActorSystemImpl): Serialization = new Serialization(system)
}