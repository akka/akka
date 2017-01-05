/*
 * Copyright (C) 2016-2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.serialization

import akka.actor.setup.ActorSystemSetup
import akka.actor.{ ActorSystem, BootstrapSetup }
import akka.testkit.AkkaSpec
import com.typesafe.config.ConfigFactory

class ConfigurationDummy
class ProgrammaticDummy

object SerializationSetupSpec {

  val testSerializer = new TestSerializer

  val serializationSettings = SerializationSetup { _ ⇒
    List(
      SerializerDetails("test", testSerializer, List(classOf[ProgrammaticDummy]))
    )
  }
  val bootstrapSettings = BootstrapSetup(None, Some(ConfigFactory.parseString("""
    akka {
      actor {
        serialize-messages = off
        serialization-bindings {
          "akka.serialization.ConfigurationDummy" = test
        }
      }
    }
    """)), None)
  val actorSystemSettings = ActorSystemSetup(bootstrapSettings, serializationSettings)

}

class SerializationSetupSpec extends AkkaSpec(
  ActorSystem("SerializationSettingsSpec", SerializationSetupSpec.actorSystemSettings)
) {

  import SerializationSetupSpec._

  "The serialization settings" should {

    "allow for programmatic configuration of serializers" in {
      val serializer = SerializationExtension(system).findSerializerFor(new ProgrammaticDummy)
      serializer shouldBe theSameInstanceAs(testSerializer)
    }

    "allow a configured binding to hook up to a programmatic serializer" in {
      val serializer = SerializationExtension(system).findSerializerFor(new ConfigurationDummy)
      serializer shouldBe theSameInstanceAs(testSerializer)
    }

  }

}
