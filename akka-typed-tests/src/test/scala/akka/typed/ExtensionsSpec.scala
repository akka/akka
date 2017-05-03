/**
 * Copyright (C) 2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.typed

import com.typesafe.config.ConfigFactory
import org.scalatest.concurrent.ScalaFutures

import scala.concurrent.Future

class DummyExtension1 extends Extension
object DummyExtension1 extends ExtensionId[DummyExtension1] {
  def createExtension(system: ActorSystem[_]) = new DummyExtension1
}

class SlowExtension extends Extension
object SlowExtension extends ExtensionId[SlowExtension] {
  def createExtension(system: ActorSystem[_]) = {
    Thread.sleep(25)
    new SlowExtension
  }
}

class FailingToLoadExtension extends Extension
object FailingToLoadExtension extends ExtensionId[FailingToLoadExtension] {
  def createExtension(system: ActorSystem[_]) = {
    throw new RuntimeException("I cannot be trusted!")
  }
}

class MultiExtension(val n: Int) extends Extension
class MultiExtensionId(n: Int) extends ExtensionId[MultiExtension] {
  def createExtension(system: ActorSystem[_]): MultiExtension = new MultiExtension(n)
}

class ExtensionsSpec extends TypedSpecSetup with ScalaFutures {

  object `The extensions subsystem` {

    def `01 should return the same instance for the same id`(): Unit = {
      val system = ActorSystem[Any]("ExtensionsSpec01", Behavior.EmptyBehavior)
      try {
        val instance1 = system.registerExtension(DummyExtension1)
        val instance2 = system.registerExtension(DummyExtension1)

        instance1 should be theSameInstanceAs instance2

        val instance3 = DummyExtension1(system)
        instance3 should be theSameInstanceAs instance2

        val instance4 = DummyExtension1.get(system)
        instance4 should be theSameInstanceAs instance3

      } finally {
        system.terminate().futureValue
      }
    }

    def `02 should return the same instance for the same id concurrently`(): Unit = {
      // not exactly water tight but better than nothing
      val system = ActorSystem[Any]("ExtensionsSpec02", Behavior.EmptyBehavior)
      import system.executionContext
      try {
        val futures = (0 to 1000).map(n ⇒
          Future {
            system.registerExtension(SlowExtension)
          }
        )

        val instances = Future.sequence(futures).futureValue

        instances.reduce { (a, b) ⇒
          a should be theSameInstanceAs b
          b
        }
      } finally {
        system.terminate().futureValue
      }
    }

    def `03 should load extensions from the configuration`(): Unit = {
      val system = ActorSystem[Any]("ExtensionsSpec03", Behavior.EmptyBehavior, config = Some(ConfigFactory.parseString(
        """
          akka.typed.extensions = ["akka.typed.DummyExtension1$", "akka.typed.SlowExtension$"]
        """)))

      try {

        system.hasExtension(DummyExtension1) should ===(true)
        system.extension(DummyExtension1) shouldBe a[DummyExtension1]

        system.hasExtension(SlowExtension) should ===(true)
        system.extension(SlowExtension) shouldBe a[SlowExtension]

      } finally {
        system.terminate().futureValue
      }
    }

    def `04 handle extensions that fail to initialize`(): Unit = {
      def create(): Unit = {
        ActorSystem[Any]("ExtensionsSpec04", Behavior.EmptyBehavior, config = Some(ConfigFactory.parseString(
          """
          akka.typed.extensions = ["akka.typed.FailingToLoadExtension$"]
        """)))
      }
      intercept[RuntimeException] {
        create()
      }
      // and keeps happening (a retry to create on each access)
      intercept[RuntimeException] {
        create()
      }
    }

    def `05 support multiple instances of the same type of extension (with different ids)`(): Unit = {
      val system = ActorSystem[Any]("ExtensionsSpec05", Behavior.EmptyBehavior)
      try {
        val id1 = new MultiExtensionId(1)
        val id2 = new MultiExtensionId(2)

        system.registerExtension(id1).n should ===(1)
        system.registerExtension(id2).n should ===(2)
        system.registerExtension(id1).n should ===(1)

      } finally {
        system.terminate().futureValue
      }

    }
  }

}
