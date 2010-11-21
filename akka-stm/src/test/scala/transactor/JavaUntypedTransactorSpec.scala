package akka.transactor.test

import org.scalatest.junit.JUnitWrapperSuite

class JavaUntypedTransactorSpec extends JUnitWrapperSuite(
  "akka.transactor.test.UntypedTransactorTest",
  Thread.currentThread.getContextClassLoader
)
