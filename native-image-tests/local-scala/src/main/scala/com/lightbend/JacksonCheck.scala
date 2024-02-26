/*
 * Copyright (C) 2009-2024 Lightbend Inc. <https://www.lightbend.com>
 */
package com.lightbend

import akka.actor.typed.ActorSystem
import akka.serialization.SerializationExtension
import akka.serialization.SerializerWithStringManifest
import akka.serialization.jackson.JsonSerializable
import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import com.fasterxml.jackson.annotation.JsonTypeName
import com.fasterxml.jackson.core.JsonGenerator
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.core.`type`.TypeReference
import com.fasterxml.jackson.databind.DeserializationContext
import com.fasterxml.jackson.databind.SerializerProvider
import com.fasterxml.jackson.databind.annotation.JsonDeserialize
import com.fasterxml.jackson.databind.annotation.JsonSerialize
import com.fasterxml.jackson.databind.deser.std.StdDeserializer
import com.fasterxml.jackson.databind.ser.std.StdSerializer
import com.fasterxml.jackson.module.scala.JsonScalaEnumeration

object JacksonCheck {

  // Note: class level JsonCreator does not work with native-image, it must be on the constructor
  final case class SingleParam(field: String) extends JsonSerializable

  final case class NestedTypes(field: NestedType, field2: NestedType2) extends JsonSerializable

  // these are auto-detected
  final case class NestedType(someField: String)
  final case class NestedType2(otherField: Double, yetAnother: Int)

  // from the akka-serialization-jackson docs

  // type hierarchy / ADT
  // FIXME regular JDK doesn't need this @JsonCreator annotation but graal does
  final case class Zoo(primaryAttraction: Animal) extends JsonSerializable

  // Note: native image needs additional marker trait on ADT supertype here or else
  //       AkkaJacksonSerializationFeature has no way to detect and register the subtypes for
  //       reflective access

  @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
  @JsonSubTypes(
    Array(
      new JsonSubTypes.Type(value = classOf[Lion], name = "lion"),
      new JsonSubTypes.Type(value = classOf[Elephant], name = "elephant"),
      new JsonSubTypes.Type(value = classOf[Unicorn], name = "unicorn")))
  sealed trait Animal

  final case class Lion(name: String) extends Animal
  final case class Elephant(name: String, age: Int) extends Animal

  @JsonDeserialize(`using` = classOf[UnicornDeserializer])
  sealed trait Unicorn extends Animal
  @JsonTypeName("unicorn")
  case object Unicorn extends Unicorn

  class UnicornDeserializer extends StdDeserializer[Unicorn](Unicorn.getClass) {
    // whenever we need to deserialize an instance of Unicorn trait, we return the object Unicorn
    override def deserialize(p: JsonParser, ctxt: DeserializationContext): Unicorn = Unicorn
  }

  // object enum
  @JsonSerialize(`using` = classOf[DirectionJsonSerializer])
  @JsonDeserialize(`using` = classOf[DirectionJsonDeserializer])
  sealed trait Direction

  object Direction {
    case object North extends Direction
    case object East extends Direction
    case object South extends Direction
    case object West extends Direction
  }

  class DirectionJsonSerializer extends StdSerializer[Direction](classOf[Direction]) {
    import Direction._

    override def serialize(value: Direction, gen: JsonGenerator, provider: SerializerProvider): Unit = {
      val strValue = value match {
        case North => "N"
        case East  => "E"
        case South => "S"
        case West  => "W"
      }
      gen.writeString(strValue)
    }
  }

  class DirectionJsonDeserializer extends StdDeserializer[Direction](classOf[Direction]) {
    import Direction._

    override def deserialize(p: JsonParser, ctxt: DeserializationContext): Direction = {
      p.getText match {
        case "N" => North
        case "E" => East
        case "S" => South
        case "W" => West
      }
    }
  }

  final case class Compass(currentDirection: Direction) extends JsonSerializable

  // scala enums
  object Planet extends Enumeration {
    type Planet = Value
    val Mercury, Venus, Earth, Mars, Krypton = Value
  }

  // Uses default Jackson serialization format for Scala Enumerations
  final case class Alien(name: String, planet: Planet.Planet) extends JsonSerializable

  // Serializes planet values as a JsonString
  class PlanetType extends TypeReference[Planet.type] {}

  // FIXME the deserialization sees the @JsonScalaEnumeration, but the serialization does not
  //       so the serialized form is `{"enumClass":"com.lightbend.JacksonCheck$Planet","value":"Earth"}`
  //       while the deserializer expects "Earth"
  //       Verfied that in the native image the annotation is available on the constructor parameter for reflection
  //       @com.fasterxml.jackson.module.scala.JsonScalaEnumeration(com.lightbend.JacksonCheck.PlanetType.class)
  final case class Superhero(name: String, @JsonScalaEnumeration(classOf[PlanetType]) planet: Planet.Planet)
      extends JsonSerializable

  def checkJackson(implicit system: ActorSystem[_]): String = {
    val serializationExtension = SerializationExtension(system)
    def serializationRoundtrip(someObject: AnyRef): Unit = {
      val serializer =
        serializationExtension.serializerFor(someObject.getClass).asInstanceOf[SerializerWithStringManifest]
      val manifest = serializer.manifest(someObject)
      val bytes = serializer.toBinary(someObject)
      val deserialized = serializer.fromBinary(bytes, manifest)
      require(
        someObject == deserialized,
        s"Jackson Serialization of ${someObject.getClass} doesn't work, ${someObject} isn't equal to serialized-deserialized ${deserialized}")
    }

    serializationRoundtrip(SingleParam("some text"))
    serializationRoundtrip(NestedTypes(NestedType("some text"), NestedType2(7d, 4)))
    serializationRoundtrip(Zoo(Lion("aslan")))
    serializationRoundtrip(Zoo(Unicorn))
    serializationRoundtrip(Compass(Direction.South))

    serializationRoundtrip(new JavaJacksonModels.SimpleCommand("some text"))
    serializationRoundtrip(new JavaJacksonModels.Zoo(new JavaJacksonModels.Elephant("dumbo", 23)))

    def notWorkingYet() {
      serializationRoundtrip(Alien("Clark", Planet.Krypton))
      serializationRoundtrip(Superhero("Greta", Planet.Earth))
    }

    "Akka Serialization Jackson works"
  }
}
