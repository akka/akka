package akka.transactor.test

import org.scalatest.junit.JUnitWrapperSuite

class JavaUntypedCoordinatedSpec extends JUnitWrapperSuite(
  "akka.transactor.test.UntypedCoordinatedIncrementTest",
  Thread.currentThread.getContextClassLoader
)
