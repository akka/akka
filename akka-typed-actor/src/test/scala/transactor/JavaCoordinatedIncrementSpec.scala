package akka.transactor.test

import org.scalatest.junit.JUnitWrapperSuite

class JavaCoordinatedIncrementSpec extends JUnitWrapperSuite(
  "akka.transactor.test.TypedCoordinatedIncrementTest",
  Thread.currentThread.getContextClassLoader
)
