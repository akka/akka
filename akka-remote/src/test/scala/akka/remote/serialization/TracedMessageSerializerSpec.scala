/**
 * Copyright (C) 2009-2013 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.remote.serialization

import akka.actor.{ ActorSystem, ExtendedActorSystem }
import akka.serialization.SerializationExtension
import akka.testkit.AkkaSpec
import akka.trace.{ ContextOnlyTracer, MultiContext, TracedMessage, Tracer }
import com.typesafe.config.{ Config, ConfigFactory }
import java.nio.ByteBuffer

object TracedMessageSerializerSpec {
  val testConfig: Config = ConfigFactory.parseString("""
    akka.tracers = ["akka.remote.serialization.TracedMessageSerializerSpec$Tracer1"]
  """)

  val multiConfig: Config = ConfigFactory.parseString("""
    akka.tracers = ["akka.remote.serialization.TracedMessageSerializerSpec$Tracer1", "akka.remote.serialization.TracedMessageSerializerSpec$Tracer2"]
  """)

  class Tracer1(config: Config) extends TestContextTracer(1)
  class Tracer2(config: Config) extends TestContextTracer(2)

  class TestContextTracer(i: Int) extends ContextOnlyTracer {
    def getContext(): Any = i
    def setContext(context: Any): Unit = ()
    def clearContext(): Unit = ()
    def identifier: Int = i
    def serializeContext(system: ExtendedActorSystem, context: Any): Array[Byte] = serializeInt(context)
    def deserializeContext(system: ExtendedActorSystem, context: Array[Byte]): Any = deserializeInt(context)
  }

  def serializeInt(a: Any): Array[Byte] = a match {
    case i: Int ⇒ ByteBuffer.allocate(4).putInt(i).array
    case _      ⇒ Array.empty
  }

  def deserializeInt(bytes: Array[Byte]): Int = ByteBuffer.wrap(bytes).getInt
}

class TracedMessageSerializerSpec extends AkkaSpec(TracedMessageSerializerSpec.testConfig) {

  val extendedSystem = system.asInstanceOf[ExtendedActorSystem]
  val serialization = SerializationExtension(system)
  val serializer = serialization.serializerFor(classOf[TracedMessage])

  "TracedMessageSerializer" must {

    "resolve serializer for TracedMessage" in {
      serializer.getClass should be(classOf[TracedMessageSerializer])
    }

    "serialize and deserialize TracedMessage" in {
      val message = TracedMessage("hello", extendedSystem.tracer.getContext)
      val binary = serializer.toBinary(message)
      val result = serializer.fromBinary(binary, None)

      result should be(message)
    }

    "serialize and deserialize TracedMessage for MultiTracer" in {
      val multiTracerSystem = ActorSystem("TracedMessageSerializerSpec-MultiTracer", TracedMessageSerializerSpec.multiConfig)
      val multiTracerExtendedSystem = multiTracerSystem.asInstanceOf[ExtendedActorSystem]
      val multiTracerSerializer = new TracedMessageSerializer(multiTracerExtendedSystem)

      val message = TracedMessage("hello", multiTracerExtendedSystem.tracer.getContext)
      val binary = multiTracerSerializer.toBinary(message)
      val result = multiTracerSerializer.fromBinary(binary, None)

      result should be(message)

      multiTracerSystem.shutdown()
    }

    "serialize and deserialize between different numbers of tracers" in {
      val multiTracerSystem = ActorSystem("TracedMessageSerializerSpec-MultiTracer", TracedMessageSerializerSpec.multiConfig)
      val multiTracerExtendedSystem = multiTracerSystem.asInstanceOf[ExtendedActorSystem]
      val multiTracerSerializer = new TracedMessageSerializer(multiTracerExtendedSystem)

      val single = TracedMessage("hello", extendedSystem.tracer.getContext)
      val singleBinary = serializer.toBinary(single)

      val multi = TracedMessage("hello", multiTracerExtendedSystem.tracer.getContext)
      val multiBinary = multiTracerSerializer.toBinary(multi)

      val singleToMulti = multiTracerSerializer.fromBinary(singleBinary, None)
      val multiToSingle = serializer.fromBinary(multiBinary, None)

      val expectedMultiContext = new MultiContext(Array(extendedSystem.tracer.getContext, Tracer.emptyContext))
      val expectedMulti = TracedMessage("hello", expectedMultiContext)

      singleToMulti should be(expectedMulti)
      multiToSingle should be(single)

      multiTracerSystem.shutdown()
    }
  }
}
