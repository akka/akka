/**
 * Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.docs.agent

import org.scalatest.junit.JUnitWrapperSuite

class AgentDocJavaSpec extends JUnitWrapperSuite(
  "akka.docs.agent.AgentDocTest",
  Thread.currentThread.getContextClassLoader)