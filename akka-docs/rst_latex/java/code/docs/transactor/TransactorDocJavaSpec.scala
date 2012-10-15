/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */

package docs.transactor

import org.scalatest.junit.JUnitWrapperSuite

class TransactorDocJavaSpec extends JUnitWrapperSuite(
  "docs.transactor.TransactorDocTest",
  Thread.currentThread.getContextClassLoader)