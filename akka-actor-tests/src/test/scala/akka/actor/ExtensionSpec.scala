/**
 * Copyright (C) 2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.actor

import java.util.concurrent.atomic.AtomicInteger

import akka.testkit.EventFilter
import akka.testkit.TestKit._
import com.typesafe.config.ConfigFactory
import org.scalatest.{ Matchers, WordSpec }
import org.scalatest.junit.JUnitSuiteLike

import scala.util.control.NoStackTrace

class JavaExtensionSpec extends JavaExtension with JUnitSuiteLike

object TestExtension extends ExtensionId[TestExtension] with ExtensionIdProvider {
  def lookup = this
  def createExtension(s: ExtendedActorSystem) = new TestExtension(s)
}

// Dont't place inside ActorSystemSpec object, since it will not be garbage collected and reference to system remains
class TestExtension(val system: ExtendedActorSystem) extends Extension

object FailingTestExtension extends ExtensionId[FailingTestExtension] with ExtensionIdProvider {
  def lookup = this
  def createExtension(s: ExtendedActorSystem) = new FailingTestExtension(s)

  class TestException extends IllegalArgumentException("ERR") with NoStackTrace
}

object InstanceCountingExtension extends ExtensionId[DummyExtensionImpl] with ExtensionIdProvider {
  val createCount = new AtomicInteger(0)
  override def createExtension(system: ExtendedActorSystem): DummyExtensionImpl = {
    createCount.addAndGet(1)
    new DummyExtensionImpl
  }
  override def lookup(): ExtensionId[_ <: Extension] = this
}

class DummyExtensionImpl extends Extension

// Dont't place inside ActorSystemSpec object, since it will not be garbage collected and reference to system remains
class FailingTestExtension(val system: ExtendedActorSystem) extends Extension {
  // first time the actor is created
  val ref = system.actorOf(Props.empty, "uniqueName")
  // but the extension initialization fails
  // second time it will throw exception when trying to create actor with same name,
  // but we want to see the first exception every time
  throw new FailingTestExtension.TestException
}

class ExtensionSpec extends WordSpec with Matchers {

  "The ActorSystem extensions support" should {

    "support extensions" in {
      val config = ConfigFactory.parseString("""akka.extensions = ["akka.actor.TestExtension"]""")
      val system = ActorSystem("extensions", config)

      // TestExtension is configured and should be loaded at startup
      system.hasExtension(TestExtension) should ===(true)
      TestExtension(system).system should ===(system)
      system.extension(TestExtension).system should ===(system)

      shutdownActorSystem(system)
    }

    "handle extensions that fail to initialize" in {
      val system = ActorSystem("extensions")

      intercept[FailingTestExtension.TestException] {
        FailingTestExtension(system)
      }
      // same exception should be reported next time
      intercept[FailingTestExtension.TestException] {
        FailingTestExtension(system)
      }

      shutdownActorSystem(system)
    }

    "fail the actor system if an extension listed in akka.extensions fails to start" in {
      intercept[RuntimeException] {
        val system = ActorSystem("failing", ConfigFactory.parseString(
          """
            akka.extensions = ["akka.actor.FailingTestExtension"]
          """))

        shutdownActorSystem(system)
      }
    }

    "log an error if an extension listed in akka.extensions cannot be loaded" in {
      val system = ActorSystem("failing", ConfigFactory.parseString(
        """
          akka.extensions = ["akka.actor.MissingExtension"]
        """))
      EventFilter.error("While trying to load extension [akka.actor.MissingExtension], skipping...").intercept()(system)
      shutdownActorSystem(system)

    }

    "allow for auto-loading of library-extensions" in {
      val system = ActorSystem("extensions")
      val listedExtensions = system.settings.config.getStringList("akka.library-extensions")
      listedExtensions.size should be > 0
      // could be initalized by other tests, so at least once
      InstanceCountingExtension.createCount.get() should be > 0

      shutdownActorSystem(system)
    }

    "fail the actor system if a library-extension fails to start" in {
      intercept[FailingTestExtension.TestException] {
        ActorSystem("failing", ConfigFactory.parseString(
          """
            akka.library-extensions += "akka.actor.FailingTestExtension"
          """).withFallback(ConfigFactory.load()).resolve())
      }

    }

    "fail the actor system if a library-extension cannot be loaded" in {
      intercept[RuntimeException] {
        ActorSystem("failing", ConfigFactory.parseString(
          """
            akka.library-extensions += "akka.actor.MissingExtension"
          """).withFallback(ConfigFactory.load()))
      }
    }

  }

}
