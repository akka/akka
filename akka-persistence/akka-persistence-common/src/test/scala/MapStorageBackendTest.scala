/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

package se.scalablesolutions.akka.persistence.common

import org.scalatest.matchers.ShouldMatchers
import se.scalablesolutions.akka.util.Logging
import org.scalatest.{BeforeAndAfterEach, Spec}

/**
 * Implementation Compatibility test for PersistentMap backend implementations.
 */

trait MapStorageBackendTest extends Spec with ShouldMatchers with BeforeAndAfterEach with Logging {
  def storage: MapStorageBackend[Array[Byte], Array[Byte]]

  def dropMaps: Unit

  override def beforeEach = {
    log.info("beforeEach: dropping maps")
    dropMaps
  }

  override def afterEach = {
    log.info("afterEach: dropping maps")
    dropMaps
  }


  describe("A Properly functioning MapStorageBackend") {

  }

}