/**
 *  Copyright (C) 2009-2012 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.actor

import akka.testkit._
import org.scalatest.junit.JUnitSuite
import com.typesafe.config.ConfigFactory

class JavaExtensionSpec extends JavaExtension with JUnitSuite

object TestExtension extends ExtensionId[TestExtension] with ExtensionIdProvider {
  def lookup = this
  def createExtension(s: ActorSystemImpl) = new TestExtension(s)
}

// Dont't place inside ActorSystemSpec object, since it will not be garbage collected and reference to system remains
class TestExtension(val system: ActorSystemImpl) extends Extension

class ActorSystemSpec extends AkkaSpec("""akka.extensions = ["akka.actor.TestExtension$"]""") {

  "An ActorSystem" must {

    "support extensions" in {
      TestExtension(system).system must be === system
      system.extension(TestExtension).system must be === system
      system.hasExtension(TestExtension) must be(true)
    }

  }

}