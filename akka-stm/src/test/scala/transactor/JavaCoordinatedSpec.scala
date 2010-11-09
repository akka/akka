package akka.transactor.test

import org.scalatest.junit.JUnitWrapperSuite

class JavaCoordinatedSpec extends JUnitWrapperSuite("akka.transactor.test.CoordinatedIncrementTest", Thread.currentThread.getContextClassLoader)
