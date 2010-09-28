/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

package se.scalablesolutions.akka.persistence.common

import org.scalatest.matchers.ShouldMatchers
import se.scalablesolutions.akka.util.Logging
import org.scalatest.{BeforeAndAfterEach, Spec}

/**
 * Implementation Compatibility test for PersistentQueue backend implementations.
 */

trait QueueStorageBackendTest extends Spec with ShouldMatchers with BeforeAndAfterEach with Logging {
  def storage: QueueStorageBackend[Array[Byte]]

  def dropQueues: Unit

  override def beforeEach = {
    log.info("beforeEach: dropping queues")
    dropQueues
  }

  override def afterEach = {
    log.info("afterEach: dropping queues")
    dropQueues
  }


  describe("A Properly functioning QueueStorage Backend") {

  }

}