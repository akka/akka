/**
 * Copyright (C) 2017-2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor.typed

import akka.actor.ExtendedActorSystem

object ActorRefResolver extends ExtensionId[ActorRefResolver] {
  def get(system: ActorSystem[_]): ActorRefResolver = apply(system)

  override def createExtension(system: ActorSystem[_]): ActorRefResolver =
    new ActorRefResolver(system)
}

/**
 * Serialization and deserialization of `ActorRef`.
 */
class ActorRefResolver(system: ActorSystem[_]) extends Extension {
  import akka.actor.typed.scaladsl.adapter._

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

