/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.docs.transactor

import org.scalatest.junit.JUnitWrapperSuite

class TransactorDocJavaSpec extends JUnitWrapperSuite(
  "akka.docs.transactor.TransactorDocTest",
  Thread.currentThread.getContextClassLoader)