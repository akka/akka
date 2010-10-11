/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

package se.scalablesolutions.akka.jta

import org.scalatest.junit.JUnitSuite
import org.junit.Test
import se.scalablesolutions.akka.util.ReflectiveAccess

class ReflectiveAccessSpec extends JUnitSuite {
  @Test def ensureReflectiveAccessCanLoadTransactionContainer {
    ReflectiveAccess.JtaModule.ensureJtaEnabled
    assert(ReflectiveAccess.JtaModule.transactionContainerObjectInstance.isDefined)
  }
}