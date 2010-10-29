package akka.stm

import org.scalatest.junit.JUnitWrapperSuite

class JavaStmSpec extends JUnitWrapperSuite("akka.stm.JavaStmTests", Thread.currentThread.getContextClassLoader)
