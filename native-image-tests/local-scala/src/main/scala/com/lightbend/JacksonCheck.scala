/*
 * Copyright (C) 2009-2024 Lightbend Inc. <https://www.lightbend.com>
 */
package com.lightbend

import akka.actor.typed.ActorSystem
import akka.serialization.SerializationExtension
import akka.serialization.SerializerWithStringManifest
import akka.serialization.jackson.JsonSerializable
import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonProperty
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

  // from the akka-serialization-jackson docs

  // type hierarchy / ADT
  // FIXME: Still haven't figured out why @JsonCreator cannot be used here
  final case class Zoo(@JsonProperty("primaryAttraction") primaryAttraction: Animal) extends JsonSerializable

  // Note: native image needs additional marker trait on ADT supertype here or else
  //       AkkaJacksonSerializationFeature has no way to detect and register the subtypes for
  //       reflective access

  @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, property = "type")
  @JsonSubTypes(
    Array(
      new JsonSubTypes.Type(value = classOf[Lion], name = "lion"),
      new JsonSubTypes.Type(value = classOf[Elephant], name = "elephant"),
      new JsonSubTypes.Type(value = classOf[Unicorn], name = "unicorn")))
  sealed trait Animal extends JsonSerializable

  final case class Lion(@JsonProperty("name") name: String) extends Animal
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
  sealed trait Direction extends JsonSerializable

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

  final case class Compass(@JsonProperty("currentDirection") currentDirection: Direction) extends JsonSerializable

  // scala enums
  object Planet extends Enumeration with JsonSerializable {
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

    serializationRoundtrip(Zoo(Lion("aslan")))
    serializationRoundtrip(Zoo(Unicorn))
    serializationRoundtrip(Compass(Direction.South))
    // serializationRoundtrip(Superhero("Greta", Planet.Earth))
    // serializationRoundtrip(Superhero("Clark", Planet.Krypton))

    "Akka Serialization Jackson works"
  }
}
