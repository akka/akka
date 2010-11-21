package akka.stm.test

import org.scalatest.junit.JUnitWrapperSuite

class JavaStmSpec extends JUnitWrapperSuite("akka.stm.test.JavaStmTests", Thread.currentThread.getContextClassLoader)
