/**
 *  Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.actor

import akka.testkit._
import org.scalatest.junit.JUnitSuite
import com.typesafe.config.ConfigFactory

class JavaExtensionSpec extends JavaExtension with JUnitSuite

object ActorSystemSpec {
  object TestExtension extends Extension[ActorSystem] with ExtensionProvider {
    def lookup = this
    def createExtension(s: ActorSystemImpl) = s
  }
}

class ActorSystemSpec extends AkkaSpec("""akka.extensions = ["akka.actor.ActorSystemSpec$TestExtension$"]""") {
  import ActorSystemSpec._

  "An ActorSystem" must {

    "support extensions" in {
      TestExtension(system) must be === system
      system.extension(TestExtension) must be === system
      system.hasExtension(TestExtension) must be(true)
    }

  }

}