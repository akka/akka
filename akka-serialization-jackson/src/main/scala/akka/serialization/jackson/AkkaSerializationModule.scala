/*
 * Copyright (C) 2019-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.serialization.jackson

import com.fasterxml.jackson.core.{ JsonGenerator, JsonParser, ObjectCodec }
import com.fasterxml.jackson.databind.{ DeserializationContext, JsonNode, SerializerProvider }
import com.fasterxml.jackson.databind.deser.std.StdScalarDeserializer
import com.fasterxml.jackson.databind.ser.std.StdScalarSerializer
import akka.annotation.InternalApi
import akka.serialization.{ JacksonUseAkkaSerialization, SerializationExtension, Serializer, Serializers }

/**
 * INTERNAL API: Adds support for serializing any type using AkkaSerialization
 */
@InternalApi private[akka] trait AkkaSerializationModule extends JacksonModule {
  addSerializer(
    classOf[JacksonUseAkkaSerialization],
    () => AkkaSerializationSerializer.instance,
    () => AkkaSerializationDeserializer.instance)
}

/**
 * INTERNAL API
 */
@InternalApi private[akka] object AkkaSerializationSerializer {
  val instance: AkkaSerializationSerializer = new AkkaSerializationSerializer
}

/**
 * INTERNAL API
 */
@InternalApi private[akka] class AkkaSerializationSerializer
    extends StdScalarSerializer[JacksonUseAkkaSerialization](classOf[JacksonUseAkkaSerialization])
    with ActorSystemAccess {
  private val serialization = SerializationExtension(currentSystem())
  override def serialize(
      value: JacksonUseAkkaSerialization,
      jgen: JsonGenerator,
      provider: SerializerProvider): Unit = {
    val serializer: Serializer = serialization.findSerializerFor(value)
    val serId = serializer.identifier
    val manifest = Serializers.manifestFor(serializer, value)
    val serialized = serializer.toBinary(value)
    jgen.writeStartObject()
    jgen.writeStringField("serId", serId.toString)
    jgen.writeStringField("serManifest", manifest)
    jgen.writeBinaryField("payload", serialized)
    jgen.writeEndObject()
  }
}

/**
 * INTERNAL API
 */
@InternalApi private[akka] object AkkaSerializationDeserializer {
  val instance: AkkaSerializationDeserializer = new AkkaSerializationDeserializer
}

/**
 * INTERNAL API
 */
@InternalApi private[akka] class AkkaSerializationDeserializer
    extends StdScalarDeserializer[JacksonUseAkkaSerialization](classOf[JacksonUseAkkaSerialization])
    with ActorSystemAccess {

  private val serialization = SerializationExtension(currentSystem())

  def deserialize(jp: JsonParser, ctxt: DeserializationContext): JacksonUseAkkaSerialization = {
    val codec: ObjectCodec = jp.getCodec()
    val jsonNode = codec.readTree[JsonNode](jp)
    val id = jsonNode.get("serId").textValue().toInt
    val manifest = jsonNode.get("serManifest").textValue()
    val payload = jsonNode.get("payload").binaryValue()
    serialization.deserialize(payload, id, manifest).get.asInstanceOf[JacksonUseAkkaSerialization]
  }
}
