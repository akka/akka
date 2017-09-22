/**
 * Copyright (C) 2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.typed.cluster

import java.nio.charset.StandardCharsets

import akka.typed.ActorSystem
import akka.typed.Extension
import akka.typed.ExtensionId
import akka.typed.ActorRef
import akka.typed.scaladsl.adapter._
import akka.actor.ExtendedActorSystem
import akka.annotation.InternalApi
import akka.serialization.{ BaseSerializer, SerializerWithStringManifest }

object ActorRefResolver extends ExtensionId[ActorRefResolver] {
  def get(system: ActorSystem[_]): ActorRefResolver = apply(system)

  override def createExtension(system: ActorSystem[_]): ActorRefResolver =
    new ActorRefResolver(system)
}

/**
 * Serialization and deserialization of `ActorRef`.
 */
class ActorRefResolver(system: ActorSystem[_]) extends Extension {
  import akka.typed.scaladsl.adapter._

  private val untypedSystem = system.toUntyped.asInstanceOf[ExtendedActorSystem]

  /**
   * Generate full String representation including the uid for the actor cell
   * instance as URI fragment, replacing the Address in the RootActor Path
   * with the local one unless this path’s address includes host and port
   * information. This representation should be used as serialized
   * representation.
   */
  def toSerializationFormat[T](ref: ActorRef[T]): String =
    ref.path.toSerializationFormatWithAddress(untypedSystem.provider.getDefaultAddress)

  /**
   * Deserialize an `ActorRef` in the [[#toSerializationFormat]].
   */
  def resolveActorRef[T](serializedActorRef: String): ActorRef[T] =
    untypedSystem.provider.resolveActorRef(serializedActorRef)
}

