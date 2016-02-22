/**
 * Copyright (C) 2014-2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.persistence.japi.snapshot

import akka.persistence.snapshot.{ SnapshotStoreSpec }
import com.typesafe.config.Config
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner

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
@RunWith(classOf[JUnitRunner])
class JavaSnapshotStoreSpec(config: Config) extends SnapshotStoreSpec(config)
