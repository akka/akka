/*
 * Copyright (C) 2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.scaladsl.marshallers.sprayjson

import akka.actor.ActorSystem
import akka.http.scaladsl.marshalling.Marshal
import akka.http.scaladsl.model.MessageEntity
import akka.http.scaladsl.unmarshalling.Unmarshal
import akka.stream.ActorMaterializer
import akka.util.ByteString
import org.scalatest._
import org.scalatest.concurrent.ScalaFutures
import spray.json.{ JsArray, JsString, JsValue }

class SprayJsonSupportSpec extends WordSpec with Matchers with ScalaFutures {
  import SprayJsonSupport._
  import SprayJsonSupportSpec._
  import spray.json.DefaultJsonProtocol._

  implicit val exampleFormat = jsonFormat1(Example.apply)
  implicit val sys = ActorSystem("SprayJsonSupportSpec")
  implicit val mat = ActorMaterializer()
  import sys.dispatcher

  val TestString = "Contains all UTF-8 characters: 2-byte: £, 3-byte: ﾖ, 4-byte: 😁, 4-byte as a literal surrogate pair: \uD83D\uDE01"

  "SprayJsonSupport" should {
    "allow round trip via Marshal / Unmarshal case class <-> HttpEntity" in {
      val init = Example(TestString)

      val js = Marshal(init).to[MessageEntity].futureValue
      val example = Unmarshal(js).to[Example].futureValue

      example should ===(init)
    }
    "allow round trip via Marshal / Unmarshal JsValue <-> HttpEntity" in {
      val init = JsArray(JsString(TestString))

      val js = Marshal(init).to[MessageEntity].futureValue
      val example = Unmarshal(js).to[JsValue].futureValue

      example should ===(init)
    }
    "allow Unmarshalling from ByteString -> case class" in {
      val init = Example(TestString)
      val js = ByteString(s"""{"username": "$TestString"}""")
      val example = Unmarshal(js).to[Example].futureValue

      example should ===(init)
    }
  }
}

object SprayJsonSupportSpec {
  case class Example(username: String)
}
