/**
 * Copyright (C) 2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.typed

import java.util.concurrent.atomic.AtomicInteger

import com.typesafe.config.{ Config, ConfigFactory }
import org.scalatest.concurrent.ScalaFutures

import scala.concurrent.{ Await, Future }

class DummyExtension1 extends Extension
object DummyExtension1 extends ExtensionId[DummyExtension1] {
  def createExtension(system: ActorSystem[_]) = new DummyExtension1
  def get(system: ActorSystem[_]): DummyExtension1 = apply(system)
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

object InstanceCountingExtension extends ExtensionId[DummyExtension1] {
  val createCount = new AtomicInteger(0)
  override def createExtension(system: ActorSystem[_]): DummyExtension1 = {
    createCount.addAndGet(1)
    new DummyExtension1
  }
}

class ExtensionsSpec extends TypedSpecSetup {

  object `The extensions subsystem` {

    def `01 should return the same instance for the same id`(): Unit =
      withEmptyActorSystem("ExtensionsSpec01") { system ⇒
        val instance1 = system.registerExtension(DummyExtension1)
        val instance2 = system.registerExtension(DummyExtension1)

        instance1 should be theSameInstanceAs instance2

        val instance3 = DummyExtension1(system)
        instance3 should be theSameInstanceAs instance2

        val instance4 = DummyExtension1.get(system)
        instance4 should be theSameInstanceAs instance3
      }

    def `02 should return the same instance for the same id concurrently`(): Unit =
      withEmptyActorSystem("ExtensionsSpec02") { system ⇒
        // not exactly water tight but better than nothing
        import system.executionContext
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
      }

    def `03 should load extensions from the configuration`(): Unit =
      withEmptyActorSystem("ExtensionsSpec03", Some(ConfigFactory.parseString(
        """
          akka.typed.extensions = ["akka.typed.DummyExtension1$", "akka.typed.SlowExtension$"]
        """))
      ) { system ⇒
        system.hasExtension(DummyExtension1) should ===(true)
        system.extension(DummyExtension1) shouldBe a[DummyExtension1]

        system.hasExtension(SlowExtension) should ===(true)
        system.extension(SlowExtension) shouldBe a[SlowExtension]
      }

    def `04 handle extensions that fail to initialize`(): Unit = {
      def create(): Unit = {
        ActorSystem[Any](Behavior.EmptyBehavior, "ExtensionsSpec04", config = Some(ConfigFactory.parseString(
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

    def `05 support multiple instances of the same type of extension (with different ids)`(): Unit =
      withEmptyActorSystem("ExtensionsSpec06") { system ⇒
        val id1 = new MultiExtensionId(1)
        val id2 = new MultiExtensionId(2)

        system.registerExtension(id1).n should ===(1)
        system.registerExtension(id2).n should ===(2)
        system.registerExtension(id1).n should ===(1)
      }

    def `06 allow for auto-loading of library-extensions`(): Unit =
      withEmptyActorSystem("ExtensionsSpec06") { system ⇒
        val listedExtensions = system.settings.config.getStringList("akka.typed.library-extensions")
        listedExtensions.size should be > 0
        // could be initalized by other tests, so at least once
        InstanceCountingExtension.createCount.get() should be > 0
      }

    def `07 fail the system if a library-extension cannot be loaded`(): Unit =
      intercept[RuntimeException] {
        withEmptyActorSystem(
          "ExtensionsSpec07",
          Some(ConfigFactory.parseString("""akka.library-extensions += "akka.typed.FailingToLoadExtension$"""))
        ) { _ ⇒ () }
      }

    def `08 fail the system if a library-extension cannot be loaded`(): Unit =
      intercept[RuntimeException] {
        withEmptyActorSystem(
          "ExtensionsSpec08",
          Some(ConfigFactory.parseString("""akka.library-extensions += "akka.typed.MissingExtension"""))
        ) { _ ⇒ () }
      }

    def `09 load an extension implemented in Java`(): Unit =
      withEmptyActorSystem("ExtensionsSpec09") { system ⇒
        // no way to make apply work cleanly with extensions implemented in Java
        val instance1 = ExtensionsTest.MyExtension.get(system)
        val instance2 = ExtensionsTest.MyExtension.get(system)

        instance1 should be theSameInstanceAs instance2
      }

    def `10 not create an extension multiple times when using the ActorSystemAdapter`(): Unit = {
      import akka.typed.scaladsl.adapter._
      val untypedSystem = akka.actor.ActorSystem()
      try {

        val before = InstanceCountingExtension.createCount.get()
        InstanceCountingExtension(untypedSystem.toTyped)
        val ext = InstanceCountingExtension(untypedSystem.toTyped)
        val after = InstanceCountingExtension.createCount.get()

        (after - before) should ===(1)

      } finally {
        untypedSystem.terminate().futureValue
      }
    }

    def withEmptyActorSystem[T](name: String, config: Option[Config] = None)(f: ActorSystem[_] ⇒ T): T = {
      val system = ActorSystem[Any](Behavior.EmptyBehavior, name, config = config)
      try f(system) finally system.terminate().futureValue
    }

  }
}
