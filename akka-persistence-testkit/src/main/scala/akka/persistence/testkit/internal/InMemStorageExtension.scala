/*
 * Copyright (C) 2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.testkit.internal

import akka.actor.{ ActorSystem, ExtendedActorSystem, ExtensionId, ExtensionIdProvider }
import akka.annotation.InternalApi
import akka.persistence.testkit.EventStorage
import akka.persistence.testkit.scaladsl.PersistenceTestKit

/**
 * INTERNAL API
 */
@InternalApi
private[testkit] object InMemStorageExtension extends ExtensionId[EventStorage] with ExtensionIdProvider {

  override def get(system: ActorSystem): EventStorage = super.get(system)

  override def createExtension(system: ExtendedActorSystem) =
    if (PersistenceTestKit.Settings(system).serialize) {
      new SerializedEventStorageImpl(system)
    } else {
      new SimpleEventStorageImpl
    }

  override def lookup() = InMemStorageExtension

}
