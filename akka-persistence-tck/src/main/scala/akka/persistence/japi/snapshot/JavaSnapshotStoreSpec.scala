/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.persistence.japi.snapshot

import akka.persistence.snapshot.{ SnapshotStore, SnapshotStoreSpec }
import com.typesafe.config.Config
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner

/**
 * JAVA API
 *
 * This spec aims to verify custom akka-persistence [[SnapshotStore]] implementations.
 * Plugin authors are highly encouraged to include it in their plugin's test suites.
 *
 * In case your snapshot-store plugin needs some kind of setup or teardown, override the `beforeAll` or `afterAll`
 * methods (don't forget to call `super` in your overriden methods).
 *
 * @see [[akka.persistence.snapshot.SnapshotStoreSpec]]
 */
@RunWith(classOf[JUnitRunner])
class JavaSnapshotStoreSpec(val config: Config) extends SnapshotStoreSpec
