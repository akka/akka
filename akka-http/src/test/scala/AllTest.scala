package se.scalablesolutions.akka.security

import junit.framework.Test
import junit.framework.TestCase
import junit.framework.TestSuite

object AllTest extends TestCase {
  def suite(): Test = {
    val suite = new TestSuite("All Scala tests")
    suite.addTestSuite(classOf[BasicAuthenticatorSpec])
    suite
  }

  def main(args: Array[String]) = junit.textui.TestRunner.run(suite)
}
