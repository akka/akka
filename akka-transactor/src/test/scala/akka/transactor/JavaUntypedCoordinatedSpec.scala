/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.transactor

import org.scalatest.junit.JUnitWrapperSuite

class JavaUntypedCoordinatedSpec extends JUnitWrapperSuite(
  "akka.transactor.UntypedCoordinatedIncrementTest",
  Thread.currentThread.getContextClassLoader)
