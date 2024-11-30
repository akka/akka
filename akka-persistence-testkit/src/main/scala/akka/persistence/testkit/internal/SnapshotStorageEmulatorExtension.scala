/*
 * Copyright (C) 2020-2024 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.testkit.internal

import java.util.concurrent.ConcurrentHashMap

import akka.actor.{ ActorSystem, ExtendedActorSystem, ExtensionId, ExtensionIdProvider }
import akka.actor.Extension
import akka.annotation.InternalApi
import akka.persistence.testkit.SnapshotStorage
import akka.persistence.testkit.scaladsl.SnapshotTestKit

/**
 * INTERNAL API
 */
@InternalApi
private[testkit] object SnapshotStorageEmulatorExtension
    extends ExtensionId[SnapshotStorageEmulatorExtension]
    with ExtensionIdProvider {

  override def get(system: ActorSystem): SnapshotStorageEmulatorExtension = super.get(system)

  override def createExtension(system: ExtendedActorSystem): SnapshotStorageEmulatorExtension =
    new SnapshotStorageEmulatorExtension(system)

  override def lookup: ExtensionId[_ <: Extension] =
    SnapshotStorageEmulatorExtension
}

/**
 * INTERNAL API
 */
@InternalApi
final class SnapshotStorageEmulatorExtension(system: ExtendedActorSystem) extends Extension {
  private val stores = new ConcurrentHashMap[String, SnapshotStorage]()
  private lazy val shouldCreateSerializedSnapshotStorage = SnapshotTestKit.Settings(system).serialize

  def storageFor(key: String): SnapshotStorage =
    stores.computeIfAbsent(key, _ => {
      // we don't really care about the key here, we just want separate instances
      if (shouldCreateSerializedSnapshotStorage) {
        new SerializedSnapshotStorageImpl(system)
      } else {
        new SimpleSnapshotStorageImpl
      }
    })
}
