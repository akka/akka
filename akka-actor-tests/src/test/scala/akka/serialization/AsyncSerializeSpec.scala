/*
 * Copyright (C) 2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.serialization

import akka.actor.ExtendedActorSystem
import akka.serialization.AsyncSerializeSpec.{ Message1, TestAsyncSerializer }
import akka.testkit.{ AkkaSpec, EventFilter }
import com.typesafe.config.ConfigFactory

import scala.concurrent.Future

object AsyncSerializeSpec {

  case class Message1(str: String)
  case class Message2(str: String)

  val config = ConfigFactory.parseString(
    """
       akka {
        actor {
          serializers {
            async = "akka.serialization.AsyncSerializeSpec$TestAsyncSerializer"
          }

          serialization-bindings = {
            "akka.serialization.AsyncSerializeSpec$Message1" = async
            "akka.serialization.AsyncSerializeSpec$Message2" = async
          }
        }
       }
    """)

  class TestAsyncSerializer(system: ExtendedActorSystem) extends AsyncSerializerWithStringManifest(system) {

    override def toBinaryAsync(o: AnyRef): Future[Array[Byte]] = {
      o match {
        case Message1(msg) ⇒ Future.successful(msg.getBytes)
        case Message2(msg) ⇒ Future.successful(msg.getBytes)
      }
    }

    override def fromBinaryAsync(bytes: Array[Byte], manifest: String): Future[AnyRef] = {
      manifest match {
        case "1" ⇒ Future.successful(Message1(new String(bytes)))
        case "2" ⇒ Future.successful(Message2(new String(bytes)))
      }
    }

    override def identifier: Int = 9000

    override def manifest(o: AnyRef): String = o match {
      case _: Message1 ⇒ "1"
      case _: Message2 ⇒ "2"
    }
  }

}

class AsyncSerializeSpec extends AkkaSpec(AsyncSerializeSpec.config) {
  val ser = SerializationExtension(system)

  "SerializationExtension" must {
    "find async serializers" in {
      val o1 = Message1("to async")
      val serializer: Serializer = ser.findSerializerFor(o1)
      serializer.getClass shouldEqual classOf[TestAsyncSerializer]
      val asyncSerializer = serializer.asInstanceOf[SerializerWithStringManifest with AsyncSerializer]
      val binary = asyncSerializer.toBinaryAsync(o1).futureValue
      val back = asyncSerializer.fromBinaryAsync(binary, asyncSerializer.manifest(o1)).futureValue
      o1 shouldEqual back
    }

    "logs warning if sync methods called" in {
      EventFilter.warning(start = "Async serializer called synchronously", occurrences = 1) intercept {
        ser.serialize(Message1("to async"))
      }
    }
  }
}
