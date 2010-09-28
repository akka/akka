/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

package se.scalablesolutions.akka.persistence.common

import org.scalatest.matchers.ShouldMatchers
import se.scalablesolutions.akka.util.Logging
import org.scalatest.{BeforeAndAfterEach, Spec}

/**
 * Implementation Compatibility test for PersistentVector backend implementations.
 */

trait VectorStorageBackendTest extends Spec with ShouldMatchers with BeforeAndAfterEach with Logging {
  def storage: VectorStorageBackend[Array[Byte]]

  def dropVectors: Unit

  override def beforeEach = {
    log.info("beforeEach: dropping vectors")
    dropVectors
  }

  override def afterEach = {
    log.info("afterEach: dropping vectors")
    dropVectors
  }


  describe("A Properly functioning VectorStorageBackend") {

  }

}