/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

package akka.persistence.common

import org.scalatest.matchers.ShouldMatchers
import akka.util.Logging
import org.scalatest.{BeforeAndAfterEach, Spec}

/**
 * Implementation Compatibility test for PersistentSortedSet backend implementations.
 */

trait SortedSetStorageBackendTest extends Spec with ShouldMatchers with BeforeAndAfterEach with Logging {
  def storage: SortedSetStorageBackend[Array[Byte]]

  def dropSortedSets: Unit

  override def beforeEach = {
    log.info("beforeEach: dropping sorted sets")
    dropSortedSets
  }

  override def afterEach = {
    log.info("afterEach: dropping sorted sets")
    dropSortedSets
  }


  describe("A Properly functioning SortedSetStorageBackend Backend") {

  }

}
