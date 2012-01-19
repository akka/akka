/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.transactor

import org.scalatest.junit.JUnitWrapperSuite

class JavaUntypedTransactorSpec extends JUnitWrapperSuite(
  "akka.transactor.UntypedTransactorTest",
  Thread.currentThread.getContextClassLoader)
