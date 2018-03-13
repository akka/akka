/**
 * Copyright (C) 2017-2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.actor.typed

import java.util.concurrent.atomic.AtomicInteger

import com.typesafe.config.{ Config, ConfigFactory }

import scala.concurrent.Future

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

class ExtensionsSpec extends TypedAkkaSpec {

  "The extensions subsystem" must {
    "return the same instance for the same id" in
      withEmptyActorSystem("ExtensionsSpec01") { system ⇒
        val instance1 = system.registerExtension(DummyExtension1)
        val instance2 = system.registerExtension(DummyExtension1)

        instance1 should be theSameInstanceAs instance2

        val instance3 = DummyExtension1(system)
        instance3 should be theSameInstanceAs instance2

        val instance4 = DummyExtension1.get(system)
        instance4 should be theSameInstanceAs instance3
      }

    "return the same instance for the same id concurrently" in
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

    "load extensions from the configuration" in
      withEmptyActorSystem("ExtensionsSpec03", Some(ConfigFactory.parseString(
        """
          akka.typed.extensions = ["akka.actor.typed.DummyExtension1$", "akka.actor.typed.SlowExtension$"]
        """))
      ) { system ⇒
        system.hasExtension(DummyExtension1) should ===(true)
        system.extension(DummyExtension1) shouldBe a[DummyExtension1]

        system.hasExtension(SlowExtension) should ===(true)
        system.extension(SlowExtension) shouldBe a[SlowExtension]
      }

    "handle extensions that fail to initialize" in {
      def create(): Unit = {
        ActorSystem[Any](Behavior.EmptyBehavior, "ExtensionsSpec04", config = Some(ConfigFactory.parseString(
          """
          akka.typed.extensions = ["akka.actor.typed.FailingToLoadExtension$"]
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

    "support multiple instances of the same type of extension (with different ids)" in
      withEmptyActorSystem("ExtensionsSpec06") { system ⇒
        val id1 = new MultiExtensionId(1)
        val id2 = new MultiExtensionId(2)

        system.registerExtension(id1).n should ===(1)
        system.registerExtension(id2).n should ===(2)
        system.registerExtension(id1).n should ===(1)
      }

    "allow for auto-loading of library-extensions" in
      withEmptyActorSystem("ExtensionsSpec06") { system ⇒
        val listedExtensions = system.settings.config.getStringList("akka.typed.library-extensions")
        listedExtensions.size should be > 0
        // could be initalized by other tests, so at least once
        InstanceCountingExtension.createCount.get() should be > 0
      }

    "fail the system if a library-extension cannot be loaded" in
      intercept[RuntimeException] {
        withEmptyActorSystem(
          "ExtensionsSpec07",
          Some(ConfigFactory.parseString("""akka.typed.library-extensions += "akka.actor.typed.FailingToLoadExtension$""""))
        ) { _ ⇒ () }
      }

    "fail the system if a library-extension is missing" in
      intercept[RuntimeException] {
        withEmptyActorSystem(
          "ExtensionsSpec08",
          Some(ConfigFactory.parseString("""akka.typed.library-extensions += "akka.actor.typed.MissingExtension""""))
        ) { _ ⇒ () }
      }

    "load an extension implemented in Java" in
      withEmptyActorSystem("ExtensionsSpec09") { system ⇒
        // no way to make apply work cleanly with extensions implemented in Java
        val instance1 = ExtensionsTest.MyExtension.get(system)
        val instance2 = ExtensionsTest.MyExtension.get(system)

        instance1 should be theSameInstanceAs instance2
      }

    "load registered typed extensions eagerly even for untyped system" in {
      import akka.actor.typed.scaladsl.adapter._
      val beforeCreation = InstanceCountingExtension.createCount.get()
      val untypedSystem = akka.actor.ActorSystem()
      try {
        val before = InstanceCountingExtension.createCount.get()
        InstanceCountingExtension(untypedSystem.toTyped)
        val after = InstanceCountingExtension.createCount.get()

        // should have been loaded even before it was accessed in the test because InstanceCountingExtension is listed
        // as a typed library-extension in the config
        before shouldEqual beforeCreation + 1
        after shouldEqual before
      } finally {
        untypedSystem.terminate().futureValue
      }
    }

    "not create an extension multiple times when using the ActorSystemAdapter" in {
      import akka.actor.typed.scaladsl.adapter._
      val untypedSystem = akka.actor.ActorSystem()
      try {
        val ext1 = DummyExtension1(untypedSystem.toTyped)
        val ext2 = DummyExtension1(untypedSystem.toTyped)

        ext1 should be theSameInstanceAs ext2

      } finally {
        untypedSystem.terminate().futureValue
      }
    }
  }

  def withEmptyActorSystem[T](name: String, config: Option[Config] = None)(f: ActorSystem[_] ⇒ T): T = {
    val system = ActorSystem[Any](Behavior.EmptyBehavior, name, config = config)
    try f(system) finally system.terminate().futureValue
  }
}
