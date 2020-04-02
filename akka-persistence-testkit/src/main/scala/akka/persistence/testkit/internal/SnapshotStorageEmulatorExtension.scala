/*
 * Copyright (C) 2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.testkit.internal

import akka.actor.{ ActorSystem, ExtendedActorSystem, Extension, ExtensionId, ExtensionIdProvider }
import akka.annotation.InternalApi
import akka.persistence.testkit.scaladsl.SnapshotTestKit
import akka.persistence.testkit.SnapshotStorage

/**
 * INTERNAL API
 */
@InternalApi
private[testkit] object SnapshotStorageEmulatorExtension extends ExtensionId[SnapshotStorage] with ExtensionIdProvider {

  override def get(system: ActorSystem): SnapshotStorage = super.get(system)

  override def createExtension(system: ExtendedActorSystem): SnapshotStorage =
    if (SnapshotTestKit.Settings(system).serialize) {
      new SerializedSnapshotStorageImpl(system)
    } else {
      new SimpleSnapshotStorageImpl
    }

  override def lookup(): ExtensionId[_ <: Extension] =
    SnapshotStorageEmulatorExtension
}
