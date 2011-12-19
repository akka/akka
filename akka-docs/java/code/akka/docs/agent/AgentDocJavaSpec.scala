package akka.docs.agent

import org.scalatest.junit.JUnitWrapperSuite

class AgentDocJavaSpec extends JUnitWrapperSuite(
  "akka.docs.agent.AgentDocTest",
  Thread.currentThread.getContextClassLoader)