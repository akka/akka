/*
 * Copyright (C) 2016-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.serialization.jackson

import java.time.Duration
import java.time.Instant
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit
import java.util.Arrays
import java.util.Locale
import java.util.Optional
import java.util.logging.FileHandler

import scala.collection.immutable
import scala.concurrent.duration.FiniteDuration
import scala.concurrent.duration._

import akka.actor.ActorRef
import akka.actor.ActorSystem
import akka.actor.Address
import akka.actor.BootstrapSetup
import akka.actor.ExtendedActorSystem
import akka.actor.Status
import akka.actor.setup.ActorSystemSetup
import akka.actor.typed.scaladsl.Behaviors
import akka.serialization.Serialization
import akka.serialization.SerializationExtension
import akka.testkit.TestActors
import akka.testkit.TestKit
import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import com.fasterxml.jackson.core.JsonFactory
import com.fasterxml.jackson.databind.MapperFeature
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.Module
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.databind.exc.InvalidTypeIdException
import com.fasterxml.jackson.databind.node.IntNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.typesafe.config.ConfigFactory
import org.scalatest.BeforeAndAfterAll
import org.scalatest.Matchers
import org.scalatest.WordSpecLike
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.core.JsonGenerator

object ScalaTestMessages {
  trait TestMessage

  final case class SimpleCommand(name: String) extends TestMessage
  // interesting that this doesn't have the same problem with single constructor param
  // as JavaTestMessages.SimpleCommand
  final class SimpleCommandNotCaseClass(val name: String) extends TestMessage {
    override def equals(obj: Any): Boolean = obj match {
      case other: SimpleCommandNotCaseClass => other.name == name
    }
    override def hashCode(): Int = name.hashCode
  }
  final case class SimpleCommand2(name: String, name2: String) extends TestMessage
  final case class OptionCommand(maybe: Option[String]) extends TestMessage
  final case class BooleanCommand(published: Boolean) extends TestMessage
  final case class TimeCommand(timestamp: LocalDateTime, duration: FiniteDuration) extends TestMessage
  final case class InstantCommand(instant: Instant) extends TestMessage
  final case class CollectionsCommand(strings: List[String], objects: Vector[SimpleCommand]) extends TestMessage
  final case class CommandWithActorRef(name: String, replyTo: ActorRef) extends TestMessage
  final case class CommandWithTypedActorRef(name: String, replyTo: akka.actor.typed.ActorRef[String])
      extends TestMessage
  final case class CommandWithAddress(name: String, address: Address) extends TestMessage
  case object SingletonCaseObject extends TestMessage

  final case class Event1(field1: String) extends TestMessage
  final case class Event2(field1V2: String, field2: Int) extends TestMessage

  final case class Zoo(first: Animal) extends TestMessage
  @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
  @JsonSubTypes(
    Array(
      new JsonSubTypes.Type(value = classOf[Lion], name = "lion"),
      new JsonSubTypes.Type(value = classOf[Elephant], name = "elephant")))
  sealed trait Animal
  final case class Lion(name: String) extends Animal
  final case class Elephant(name: String, age: Int) extends Animal
  // not defined in JsonSubTypes
  final case class Cockroach(name: String) extends Animal

  final case class OldCommandNotInBindings(name: String)

}

class ScalaTestEventMigration extends JacksonMigration {
  override def currentVersion = 3

  override def transformClassName(fromVersion: Int, className: String): String =
    classOf[ScalaTestMessages.Event2].getName

  override def transform(fromVersion: Int, json: JsonNode): JsonNode = {
    val root = json.asInstanceOf[ObjectNode]
    root.set("field1V2", root.get("field1"))
    root.remove("field1")
    root.set("field2", IntNode.valueOf(17))
    root
  }
}

class JacksonCborSerializerSpec extends JacksonSerializerSpec("jackson-cbor")

class JacksonJsonSerializerSpec extends JacksonSerializerSpec("jackson-json") {

  def serializeToJsonString(obj: AnyRef, sys: ActorSystem = system): String = {
    val blob = serializeToBinary(obj, sys)
    new String(blob, "utf-8")
  }

  def deserializeFromJsonString(
      json: String,
      serializerId: Int,
      manifest: String,
      sys: ActorSystem = system): AnyRef = {
    val blob = json.getBytes("utf-8")
    deserializeFromBinary(blob, serializerId, manifest, sys)
  }

  "JacksonJsonSerializer" must {

    "support lookup of same ObjectMapper via JacksonObjectMapperProvider" in {
      val mapper = serialization()
        .serializerFor(classOf[JavaTestMessages.TestMessage])
        .asInstanceOf[JacksonSerializer]
        .objectMapper
      JacksonObjectMapperProvider(system).getOrCreate("jackson-json", None) shouldBe theSameInstanceAs(mapper)

      val anotherBindingName = "jackson-json2"
      val mapper2 = JacksonObjectMapperProvider(system).getOrCreate(anotherBindingName, None)
      mapper2 should not be theSameInstanceAs(mapper)
      JacksonObjectMapperProvider(system).getOrCreate(anotherBindingName, None) shouldBe theSameInstanceAs(mapper2)
    }

    "JacksonSerializer configuration" must {

      withSystem("""
        akka.actor.serializers.jackson-json2 = "akka.serialization.jackson.JacksonJsonSerializer"
        akka.actor.serialization-identifiers.jackson-json2 = 999
        akka.serialization.jackson.jackson-json2 {

          # on is Jackson's default
          serialization-features.WRITE_DURATIONS_AS_TIMESTAMPS = off

          # on is Jackson's default
          deserialization-features.EAGER_DESERIALIZER_FETCH = off

          # off is Jackson's default
          mapper-features.SORT_PROPERTIES_ALPHABETICALLY = on

          # off is Jackson's default
          json-parser-features.ALLOW_COMMENTS = on

          # on is Jackson's default
          json-generator-features.AUTO_CLOSE_TARGET = off
        }
      """) { sys =>
        val identifiedObjectMapper =
          serialization(sys).serializerByIdentity(999).asInstanceOf[JacksonJsonSerializer].objectMapper
        val namedObjectMapper = JacksonObjectMapperProvider(sys).getOrCreate("jackson-json2", None)
        val defaultObjectMapper =
          serializerFor(ScalaTestMessages.SimpleCommand("abc")).asInstanceOf[JacksonJsonSerializer].objectMapper

        "support serialization features" in {
          identifiedObjectMapper.isEnabled(SerializationFeature.WRITE_DURATIONS_AS_TIMESTAMPS) should ===(false)
          namedObjectMapper.isEnabled(SerializationFeature.WRITE_DURATIONS_AS_TIMESTAMPS) should ===(false)

          // Default mapper follows Jackson and reference.conf default configuration
          defaultObjectMapper.isEnabled(SerializationFeature.WRITE_DURATIONS_AS_TIMESTAMPS) should ===(true)
        }

        "support deserialization features" in {
          identifiedObjectMapper.isEnabled(DeserializationFeature.EAGER_DESERIALIZER_FETCH) should ===(false)
          namedObjectMapper.isEnabled(DeserializationFeature.EAGER_DESERIALIZER_FETCH) should ===(false)

          // Default mapper follows Jackson and reference.conf default configuration
          defaultObjectMapper.isEnabled(DeserializationFeature.EAGER_DESERIALIZER_FETCH) should ===(true)
        }

        "support mapper features" in {
          identifiedObjectMapper.isEnabled(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY) should ===(true)
          namedObjectMapper.isEnabled(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY) should ===(true)

          // Default mapper follows Jackson and reference.conf default configuration
          defaultObjectMapper.isEnabled(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY) should ===(false)
        }

        "support json parser features" in {
          identifiedObjectMapper.isEnabled(JsonParser.Feature.ALLOW_COMMENTS) should ===(true)
          namedObjectMapper.isEnabled(JsonParser.Feature.ALLOW_COMMENTS) should ===(true)

          // Default mapper follows Jackson and reference.conf default configuration
          defaultObjectMapper.isEnabled(JsonParser.Feature.ALLOW_COMMENTS) should ===(false)
        }

        "support json generator features" in {
          identifiedObjectMapper.isEnabled(JsonGenerator.Feature.AUTO_CLOSE_TARGET) should ===(false)
          namedObjectMapper.isEnabled(JsonGenerator.Feature.AUTO_CLOSE_TARGET) should ===(false)

          // Default mapper follows Jackson and reference.conf default configuration
          defaultObjectMapper.isEnabled(JsonGenerator.Feature.AUTO_CLOSE_TARGET) should ===(true)
        }

        "fallback to defaults when object mapper is not configured" in {
          val notConfigured = JacksonObjectMapperProvider(sys).getOrCreate("jackson-not-configured", None)
          // Use Jacksons and Akka defaults
          notConfigured.isEnabled(SerializationFeature.WRITE_DURATIONS_AS_TIMESTAMPS) should ===(true)
          notConfigured.isEnabled(DeserializationFeature.EAGER_DESERIALIZER_FETCH) should ===(true)
          notConfigured.isEnabled(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY) should ===(false)
          notConfigured.isEnabled(JsonParser.Feature.ALLOW_COMMENTS) should ===(false)
          notConfigured.isEnabled(JsonGenerator.Feature.AUTO_CLOSE_TARGET) should ===(true)
        }
      }
    }
  }

  "JacksonJsonSerializer with Java message classes" must {
    import JavaTestMessages._

    // see SerializationFeature.WRITE_DATES_AS_TIMESTAMPS = off
    "by default serialize dates and durations as text with ISO-8601 date format" in {
      // Default format is defined in com.fasterxml.jackson.databind.util.StdDateFormat
      // ISO-8601 yyyy-MM-dd'T'HH:mm:ss.SSSZ (rfc3339)
      val msg = new TimeCommand(LocalDateTime.of(2019, 4, 29, 23, 15, 3, 12345), Duration.of(5, ChronoUnit.SECONDS))
      val json = serializeToJsonString(msg)
      val expected = """{"timestamp":"2019-04-29T23:15:03.000012345","duration":"PT5S"}"""
      json should ===(expected)

      // and full round trip
      checkSerialization(msg)

      // and it can still deserialize from numeric timestamps format
      val serializer = serializerFor(msg)
      val manifest = serializer.manifest(msg)
      val serializerId = serializer.identifier
      val deserializedFromTimestampsFormat = deserializeFromJsonString(
        """{"timestamp":[2019,4,29,23,15,3,12345],"duration":5.000000000}""",
        serializerId,
        manifest)
      deserializedFromTimestampsFormat should ===(msg)
    }

    // see SerializationFeature.WRITE_DATES_AS_TIMESTAMPS = on
    "be possible to serialize dates and durations as numeric timestamps" in {
      withSystem("""
        akka.serialization.jackson.serialization-features {
          WRITE_DATES_AS_TIMESTAMPS = on
        }
        """) { sys =>
        val msg = new TimeCommand(LocalDateTime.of(2019, 4, 29, 23, 15, 3, 12345), Duration.of(5, ChronoUnit.SECONDS))
        val json = serializeToJsonString(msg, sys)
        val expected = """{"timestamp":[2019,4,29,23,15,3,12345],"duration":5.000000000}"""
        json should ===(expected)

        // and full round trip
        checkSerialization(msg, sys)

        // and it can still deserialize from ISO format
        val serializer = serializerFor(msg, sys)
        val manifest = serializer.manifest(msg)
        val serializerId = serializer.identifier
        val deserializedFromIsoFormat = deserializeFromJsonString(
          """{"timestamp":"2019-04-29T23:15:03.000012345","duration":"PT5S"}""",
          serializerId,
          manifest,
          sys)
        deserializedFromIsoFormat should ===(msg)
      }
    }

    "serialize Instant as text with ISO-8601 date format (default)" in {
      val msg = new InstantCommand(Instant.ofEpochMilli(1559907792075L))
      val json = serializeToJsonString(msg)
      val expected = """{"instant":"2019-06-07T11:43:12.075Z"}"""
      json should ===(expected)

      // and full round trip
      checkSerialization(msg)
    }

    // FAIL_ON_UNKNOWN_PROPERTIES = off is default in reference.conf
    "not fail on unknown properties" in {
      val json = """{"name":"abc","name2":"def","name3":"ghi"}"""
      val expected = new SimpleCommand2("abc", "def")
      val serializer = serializerFor(expected)
      deserializeFromJsonString(json, serializer.identifier, serializer.manifest(expected)) should ===(expected)
    }

    "be possible to create custom ObjectMapper" in {
      pending
    }
  }

  "JacksonJsonSerializer with Scala message classes" must {
    import ScalaTestMessages._

    "be possible to create custom ObjectMapper" in {
      val customJacksonObjectMapperFactory = new JacksonObjectMapperFactory {
        override def newObjectMapper(bindingName: String, jsonFactory: Option[JsonFactory]): ObjectMapper = {
          if (bindingName == "jackson-json") {
            val mapper = new ObjectMapper(jsonFactory.orNull)
            // some customer configuration of the mapper
            mapper.setLocale(Locale.US)
            mapper
          } else
            super.newObjectMapper(bindingName, jsonFactory)
        }

        override def overrideConfiguredSerializationFeatures(
            bindingName: String,
            configuredFeatures: immutable.Seq[(SerializationFeature, Boolean)])
            : immutable.Seq[(SerializationFeature, Boolean)] = {
          if (bindingName == "jackson-json")
            configuredFeatures :+ (SerializationFeature.INDENT_OUTPUT -> true)
          else
            super.overrideConfiguredSerializationFeatures(bindingName, configuredFeatures)
        }

        override def overrideConfiguredModules(
            bindingName: String,
            configuredModules: immutable.Seq[Module]): immutable.Seq[Module] =
          if (bindingName == "jackson-json")
            configuredModules.filterNot(_.isInstanceOf[JavaTimeModule])
          else
            super.overrideConfiguredModules(bindingName, configuredModules)

        override def overrideConfiguredMapperFeatures(
            bindingName: String,
            configuredFeatures: immutable.Seq[(MapperFeature, Boolean)]): immutable.Seq[(MapperFeature, Boolean)] =
          if (bindingName == "jackson-json")
            configuredFeatures :+ (MapperFeature.SORT_PROPERTIES_ALPHABETICALLY -> true)
          else
            super.overrideConfiguredMapperFeatures(bindingName, configuredFeatures)

        override def overrideConfiguredJsonParserFeatures(
            bindingName: String,
            configuredFeatures: immutable.Seq[(JsonParser.Feature, Boolean)])
            : immutable.Seq[(JsonParser.Feature, Boolean)] =
          if (bindingName == "jackson-json")
            configuredFeatures :+ (JsonParser.Feature.ALLOW_SINGLE_QUOTES -> true)
          else
            super.overrideConfiguredJsonParserFeatures(bindingName, configuredFeatures)

        override def overrideConfiguredJsonGeneratorFeatures(
            bindingName: String,
            configuredFeatures: immutable.Seq[(JsonGenerator.Feature, Boolean)])
            : immutable.Seq[(JsonGenerator.Feature, Boolean)] =
          if (bindingName == "jackson-json")
            configuredFeatures :+ (JsonGenerator.Feature.WRITE_NUMBERS_AS_STRINGS -> true)
          else
            super.overrideConfiguredJsonGeneratorFeatures(bindingName, configuredFeatures)
      }

      val config = system.settings.config

      val setup = ActorSystemSetup()
        .withSetup(JacksonObjectMapperProviderSetup(customJacksonObjectMapperFactory))
        .withSetup(BootstrapSetup(config))
      withSystem(setup) { sys =>
        val mapper = JacksonObjectMapperProvider(sys).getOrCreate("jackson-json", None)
        mapper.isEnabled(SerializationFeature.INDENT_OUTPUT) should ===(true)
        mapper.isEnabled(MapperFeature.SORT_PROPERTIES_ALPHABETICALLY) should ===(true)
        mapper.isEnabled(JsonParser.Feature.ALLOW_SINGLE_QUOTES) should ===(true)
        mapper.isEnabled(SerializationFeature.INDENT_OUTPUT) should ===(true)
        mapper.isEnabled(JsonGenerator.Feature.WRITE_NUMBERS_AS_STRINGS) should ===(true)

        val msg = InstantCommand(Instant.ofEpochMilli(1559907792075L))
        val json = serializeToJsonString(msg, sys)
        // using the custom ObjectMapper with pretty printing enabled, and no JavaTimeModule
        json should include("""  "instant" : {""")
        json should include("""    "nanos" : "75000000",""")
        json should include("""    "seconds" : "1559907792"""")
      }
    }

    "allow deserialization of classes in configured whitelist-class-prefix" in {
      val json = """{"name":"abc"}"""

      val old = SimpleCommand("abc")
      val serializer = serializerFor(old)

      val expected = OldCommandNotInBindings("abc")

      deserializeFromJsonString(json, serializer.identifier, serializer.manifest(expected)) should ===(expected)
    }
  }
}

abstract class JacksonSerializerSpec(serializerName: String)
    extends TestKit(
      ActorSystem(
        "JacksonJsonSerializerSpec",
        ConfigFactory.parseString(s"""
    akka.serialization.jackson.migrations {
      "akka.serialization.jackson.JavaTestMessages$$Event1" = "akka.serialization.jackson.JavaTestEventMigration"
      "akka.serialization.jackson.JavaTestMessages$$Event2" = "akka.serialization.jackson.JavaTestEventMigration"
      "akka.serialization.jackson.ScalaTestMessages$$Event1" = "akka.serialization.jackson.ScalaTestEventMigration"
      "akka.serialization.jackson.ScalaTestMessages$$Event2" = "akka.serialization.jackson.ScalaTestEventMigration"
    }
    akka.actor {
      serialization-bindings {
        "akka.serialization.jackson.ScalaTestMessages$$TestMessage" = $serializerName
        "akka.serialization.jackson.JavaTestMessages$$TestMessage" = $serializerName
      }
    }
    akka.serialization.jackson.whitelist-class-prefix = ["akka.serialization.jackson.ScalaTestMessages$$OldCommand"]
    """)))
    with WordSpecLike
    with Matchers
    with BeforeAndAfterAll {

  def serialization(sys: ActorSystem = system): Serialization = SerializationExtension(sys)

  override def afterAll(): Unit = {
    shutdown()
  }

  def withSystem[T](config: String)(block: ActorSystem => T): T = {
    val sys = ActorSystem(system.name, ConfigFactory.parseString(config).withFallback(system.settings.config))
    try {
      block(sys)
    } finally shutdown(sys)
  }

  def withSystem[T](setup: ActorSystemSetup)(block: ActorSystem => T): T = {
    val sys = ActorSystem(system.name, setup)
    try {
      block(sys)
    } finally shutdown(sys)
  }

  def withTransportInformation[T](sys: ActorSystem = system)(block: () => T): T = {
    Serialization.withTransportInformation(sys.asInstanceOf[ExtendedActorSystem]) { () =>
      block()
    }
  }

  def checkSerialization(obj: AnyRef, sys: ActorSystem = system): Unit = {
    val serializer = serializerFor(obj, sys)
    val manifest = serializer.manifest(obj)
    val serializerId = serializer.identifier
    val blob = serializeToBinary(obj)
    val deserialized = deserializeFromBinary(blob, serializerId, manifest, sys)
    deserialized should ===(obj)
  }

  def serializeToBinary(obj: AnyRef, sys: ActorSystem = system): Array[Byte] =
    serialization(sys).serialize(obj).get

  def deserializeFromBinary(
      blob: Array[Byte],
      serializerId: Int,
      manifest: String,
      sys: ActorSystem = system): AnyRef = {
    // TransportInformation added by serialization.deserialize
    serialization(sys).deserialize(blob, serializerId, manifest).get
  }

  def serializerFor(obj: AnyRef, sys: ActorSystem = system): JacksonSerializer =
    serialization(sys).findSerializerFor(obj) match {
      case serializer: JacksonSerializer => serializer
      case s =>
        throw new IllegalStateException(s"Wrong serializer ${s.getClass} for ${obj.getClass}")
    }

  "JacksonSerializer with Java message classes" must {
    import JavaTestMessages._

    "serialize simple message with one constructor parameter" in {
      checkSerialization(new SimpleCommand("Bob"))
    }

    "serialize simple message with two constructor parameters" in {
      checkSerialization(new SimpleCommand2("Bob", "Alice"))
      checkSerialization(new SimpleCommand2("Bob", ""))
      checkSerialization(new SimpleCommand2("Bob", null))
    }

    "serialize message with boolean property" in {
      checkSerialization(new BooleanCommand(true))
      checkSerialization(new BooleanCommand(false))
    }

    "serialize message with Optional property" in {
      checkSerialization(new OptionalCommand(Optional.of("abc")))
      checkSerialization(new OptionalCommand(Optional.empty()))
    }

    "serialize message with collections" in {
      val strings = Arrays.asList("a", "b", "c")
      val objects = Arrays.asList(new SimpleCommand("a"), new SimpleCommand("2"))
      val msg = new CollectionsCommand(strings, objects)
      checkSerialization(msg)
    }

    "serialize message with time" in {
      val msg = new TimeCommand(LocalDateTime.now(), Duration.of(5, ChronoUnit.SECONDS))
      checkSerialization(msg)
    }

    "serialize with ActorRef" in {
      val echo = system.actorOf(TestActors.echoActorProps)
      checkSerialization(new CommandWithActorRef("echo", echo))
    }

    "serialize with typed.ActorRef" in {
      import akka.actor.typed.scaladsl.adapter._
      val ref = system.spawnAnonymous(Behaviors.empty[String])
      checkSerialization(new CommandWithTypedActorRef("echo", ref))
    }

    "serialize with Address" in {
      val address = Address("akka", "sys", "localhost", 2552)
      checkSerialization(new CommandWithAddress("echo", address))
    }

    "serialize with polymorphism" in {
      checkSerialization(new Zoo(new Lion("Simba")))
      checkSerialization(new Zoo(new Elephant("Elephant", 49)))
      intercept[InvalidTypeIdException] {
        // Cockroach not listed in JsonSubTypes
        checkSerialization(new Zoo(new Cockroach("huh")))
      }
    }

    "deserialize with migrations" in {
      val event1 = new Event1("a")
      val serializer = serializerFor(event1)
      val blob = serializer.toBinary(event1)
      val event2 = serializer.fromBinary(blob, classOf[Event1].getName).asInstanceOf[Event2]
      event1.getField1 should ===(event2.getField1V2)
      event2.getField2 should ===(17)
    }

    "deserialize with migrations from V2" in {
      val event1 = new Event1("a")
      val serializer = serializerFor(event1)
      val blob = serializer.toBinary(event1)
      val event2 = serializer.fromBinary(blob, classOf[Event1].getName + "#2").asInstanceOf[Event2]
      event1.getField1 should ===(event2.getField1V2)
      event2.getField2 should ===(17)
    }
  }

  "JacksonSerializer with Scala message classes" must {
    import ScalaTestMessages._

    "serialize simple message with one constructor parameter" in {
      checkSerialization(SimpleCommand("Bob"))
      checkSerialization(new SimpleCommandNotCaseClass("Bob"))
    }

    "serialize simple message with two constructor parameters" in {
      checkSerialization(SimpleCommand2("Bob", "Alice"))
      checkSerialization(SimpleCommand2("Bob", ""))
      checkSerialization(SimpleCommand2("Bob", null))
    }

    "serialize message with boolean property" in {
      checkSerialization(BooleanCommand(true))
      checkSerialization(BooleanCommand(false))
    }

    "serialize message with Optional property" in {
      checkSerialization(OptionCommand(Some("abc")))
      checkSerialization(OptionCommand(None))
    }

    "serialize message with collections" in {
      val strings = "a" :: "b" :: "c" :: Nil
      val objects = Vector(SimpleCommand("a"), SimpleCommand("2"))
      val msg = CollectionsCommand(strings, objects)
      checkSerialization(msg)
    }

    "serialize message with time" in {
      val msg = TimeCommand(LocalDateTime.now(), 5.seconds)
      checkSerialization(msg)
    }

    "serialize FiniteDuration as java.time.Duration" in {
      withTransportInformation() { () =>
        val scalaMsg = TimeCommand(LocalDateTime.now(), 5.seconds)
        val scalaSerializer = serializerFor(scalaMsg)
        val blob = scalaSerializer.toBinary(scalaMsg)
        val javaMsg = new JavaTestMessages.TimeCommand(scalaMsg.timestamp, Duration.ofSeconds(5))
        val javaSerializer = serializerFor(javaMsg)
        val deserialized = javaSerializer.fromBinary(blob, javaSerializer.manifest(javaMsg))
        deserialized should ===(javaMsg)
      }
    }

    "serialize case object" in {
      checkSerialization(TopLevelSingletonCaseObject)
      checkSerialization(SingletonCaseObject)
    }

    "serialize with ActorRef" in {
      val echo = system.actorOf(TestActors.echoActorProps)
      checkSerialization(CommandWithActorRef("echo", echo))
    }

    "serialize with typed.ActorRef" in {
      import akka.actor.typed.scaladsl.adapter._
      val ref = system.spawnAnonymous(Behaviors.empty[String])
      checkSerialization(CommandWithTypedActorRef("echo", ref))
    }

    "serialize with Address" in {
      val address = Address("akka", "sys", "localhost", 2552)
      checkSerialization(CommandWithAddress("echo", address))
    }

    "serialize with polymorphism" in {
      checkSerialization(Zoo(Lion("Simba")))
      checkSerialization(Zoo(Elephant("Elephant", 49)))
      intercept[InvalidTypeIdException] {
        // Cockroach not listed in JsonSubTypes
        checkSerialization(Zoo(Cockroach("huh")))
      }
    }

    "deserialize with migrations" in {
      val event1 = Event1("a")
      val serializer = serializerFor(event1)
      val blob = serializer.toBinary(event1)
      val event2 = serializer.fromBinary(blob, classOf[Event1].getName).asInstanceOf[Event2]
      event1.field1 should ===(event2.field1V2)
      event2.field2 should ===(17)
    }

    "deserialize with migrations from V2" in {
      val event1 = Event1("a")
      val serializer = serializerFor(event1)
      val blob = serializer.toBinary(event1)
      val event2 = serializer.fromBinary(blob, classOf[Event1].getName + "#2").asInstanceOf[Event2]
      event1.field1 should ===(event2.field1V2)
      event2.field2 should ===(17)
    }

    "not allow serialization of blacklisted class" in {
      val serializer = serializerFor(SimpleCommand("ok"))
      val fileHandler = new FileHandler(s"target/tmp-${this.getClass.getName}")
      try {
        intercept[IllegalArgumentException] {
          serializer.manifest(fileHandler)
        }.getMessage.toLowerCase should include("blacklist")
      } finally fileHandler.close()
    }

    "not allow deserialization of blacklisted class" in {
      withTransportInformation() { () =>
        val msg = SimpleCommand("ok")
        val serializer = serializerFor(msg)
        val blob = serializer.toBinary(msg)
        intercept[IllegalArgumentException] {
          // maliciously changing manifest
          serializer.fromBinary(blob, classOf[FileHandler].getName)
        }.getMessage.toLowerCase should include("blacklist")
      }
    }

    "not allow serialization of class that is not in serialization-bindings (whitelist)" in {
      val serializer = serializerFor(SimpleCommand("ok"))
      intercept[IllegalArgumentException] {
        serializer.manifest(Status.Success("bad"))
      }.getMessage.toLowerCase should include("whitelist")
    }

    "not allow deserialization of class that is not in serialization-bindings (whitelist)" in {
      withTransportInformation() { () =>
        val msg = SimpleCommand("ok")
        val serializer = serializerFor(msg)
        val blob = serializer.toBinary(msg)
        intercept[IllegalArgumentException] {
          // maliciously changing manifest
          serializer.fromBinary(blob, classOf[Status.Success].getName)
        }.getMessage.toLowerCase should include("whitelist")
      }
    }

    "not allow serialization-bindings of open-ended types" in {
      JacksonSerializer.disallowedSerializationBindings.foreach { clazz =>
        val className = clazz.getName
        withClue(className) {
          intercept[IllegalArgumentException] {
            val sys = ActorSystem(
              system.name,
              ConfigFactory.parseString(s"""
              akka.actor.serialization-bindings {
                "$className" = $serializerName
                "akka.serialization.jackson.ScalaTestMessages$$TestMessage" = $serializerName
              }
              """).withFallback(system.settings.config))
            try {
              SerializationExtension(sys).serialize(SimpleCommand("hi")).get
            } finally shutdown(sys)
          }
        }
      }
    }

  }
}

case object TopLevelSingletonCaseObject extends ScalaTestMessages.TestMessage
