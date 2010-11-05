/**
 * Copyright (C) 2009-2010 Scalable Solutions AB <http://scalablesolutions.se>
 */

package akka.jta

import org.scalatest.junit.JUnitSuite
import org.junit.Test
import akka.stm.JtaModule

class ReflectiveAccessSpec extends JUnitSuite {
  @Test def ensureReflectiveAccessCanLoadTransactionContainer {
    JtaModule.ensureJtaEnabled
    assert(JtaModule.transactionContainerObjectInstance.isDefined)
  }
}
