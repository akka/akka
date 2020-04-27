/*
 * Copyright (C) 2014-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.japi.snapshot

import com.typesafe.config.Config

import akka.persistence.CapabilityFlag
import akka.persistence.snapshot.SnapshotStoreSpec

/**
 * JAVA API
 *
 * This spec aims to verify custom akka-persistence [[akka.persistence.snapshot.SnapshotStore]] implementations.
 * Plugin authors are highly encouraged to include it in their plugin's test suites.
 *
 * In case your snapshot-store plugin needs some kind of setup or teardown, override the `beforeAll` or `afterAll`
 * methods (don't forget to call `super` in your overridden methods).
 *
 * @see [[akka.persistence.snapshot.SnapshotStoreSpec]]
 */
class JavaSnapshotStoreSpec(config: Config) extends SnapshotStoreSpec(config) {
  override protected def supportsSerialization: CapabilityFlag = CapabilityFlag.on()
}
