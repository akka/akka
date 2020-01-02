/*
 * Copyright (C) 2019-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.serialization.jackson

import scala.concurrent.duration.FiniteDuration

import akka.annotation.InternalApi
import akka.util.JavaDurationConverters._
import com.fasterxml.jackson.core.JsonGenerator
import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.databind.DeserializationContext
import com.fasterxml.jackson.databind.SerializerProvider
import com.fasterxml.jackson.databind.deser.std.StdScalarDeserializer
import com.fasterxml.jackson.databind.ser.std.StdScalarSerializer
import com.fasterxml.jackson.datatype.jsr310.deser.DurationDeserializer
import com.fasterxml.jackson.datatype.jsr310.ser.DurationSerializer

/**
 * INTERNAL API: Adds support for serializing and deserializing [[FiniteDuration]].
 */
@InternalApi private[akka] trait FiniteDurationModule extends JacksonModule {
  addSerializer(
    classOf[FiniteDuration],
    () => FiniteDurationSerializer.instance,
    () => FiniteDurationDeserializer.instance)
}

/**
 * INTERNAL API
 */
@InternalApi private[akka] object FiniteDurationSerializer {
  val instance: FiniteDurationSerializer = new FiniteDurationSerializer
}

/**
 * INTERNAL API: Delegates to DurationSerializer in `jackson-modules-java8`
 */
@InternalApi private[akka] class FiniteDurationSerializer
    extends StdScalarSerializer[FiniteDuration](classOf[FiniteDuration]) {
  override def serialize(value: FiniteDuration, jgen: JsonGenerator, provider: SerializerProvider): Unit = {
    DurationSerializer.INSTANCE.serialize(value.asJava, jgen, provider)
  }
}

/**
 * INTERNAL API
 */
@InternalApi private[akka] object FiniteDurationDeserializer {
  val instance: FiniteDurationDeserializer = new FiniteDurationDeserializer
}

/**
 * INTERNAL API: Delegates to DurationDeserializer in `jackson-modules-java8`
 */
@InternalApi private[akka] class FiniteDurationDeserializer
    extends StdScalarDeserializer[FiniteDuration](classOf[FiniteDuration]) {

  def deserialize(jp: JsonParser, ctxt: DeserializationContext): FiniteDuration = {
    DurationDeserializer.INSTANCE.deserialize(jp, ctxt).asScala
  }
}
