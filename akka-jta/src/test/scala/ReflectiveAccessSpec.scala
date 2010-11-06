/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

package akka.jta

import org.scalatest.junit.JUnitSuite
import org.junit.Test
import akka.stm.ReflectiveJtaModule

class ReflectiveAccessSpec extends JUnitSuite {
  @Test def ensureReflectiveAccessCanLoadTransactionContainer {
    ReflectiveJtaModule.ensureJtaEnabled
    assert(ReflectiveJtaModule.transactionContainerObjectInstance.isDefined)
  }
}
