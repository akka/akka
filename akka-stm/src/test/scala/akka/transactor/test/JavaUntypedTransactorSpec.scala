package akka.transactor

import org.scalatest.junit.JUnitWrapperSuite

class JavaUntypedTransactorSpec extends JUnitWrapperSuite(
  "akka.transactor.test.UntypedTransactorTest",
  Thread.currentThread.getContextClassLoader)
