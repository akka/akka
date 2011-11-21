/**
 *  Copyright (C) 2009-2011 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.actor

import akka.testkit._
import akka.config.Configuration
import org.scalatest.junit.JUnitSuite

class JavaExtensionSpec extends JavaExtension with JUnitSuite

object ActorSystemSpec {

  // do not remove, needed for JavaExtension test
  val javaconfig = Configuration("akka.extensions" -> Seq("akka.actor.JavaExtension$TestExtension"))

  case class TestExtension extends Extension[TestExtension] {
    var system: ActorSystemImpl = _

    def init(system: ActorSystemImpl): ExtensionKey[TestExtension] = {
      this.system = system
      TestExtension
    }
  }

  object TestExtension extends ExtensionKey[TestExtension]

}

class ActorSystemSpec extends AkkaSpec(Configuration("akka.extensions" -> Seq("akka.actor.ActorSystemSpec$TestExtension"))) {
  import ActorSystemSpec._

  "An ActorSystem" must {

    "support extensions" in {
      system.extension(TestExtension).system must be === system
    }

  }

}