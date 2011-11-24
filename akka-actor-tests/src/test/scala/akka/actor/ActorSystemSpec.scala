/**
 *  Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.actor

import akka.testkit._
import org.scalatest.junit.JUnitSuite
import com.typesafe.config.ConfigFactory

class JavaExtensionSpec extends JavaExtension with JUnitSuite

object ActorSystemSpec {

  class TestExtension extends Extension[TestExtension] {
    var system: ActorSystemImpl = _

    def key = TestExtension

    def init(system: ActorSystemImpl) {
      this.system = system
    }
  }

  object TestExtension extends ExtensionKey[TestExtension]

}

class ActorSystemSpec extends AkkaSpec("""akka.extensions = ["akka.actor.ActorSystemSpec$TestExtension"]""") {
  import ActorSystemSpec._

  "An ActorSystem" must {

    "support extensions" in {
      system.extension(TestExtension).system must be === system
    }

  }

}