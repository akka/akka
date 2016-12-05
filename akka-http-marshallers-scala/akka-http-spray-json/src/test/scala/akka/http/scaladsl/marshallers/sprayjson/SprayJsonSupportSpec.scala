/*
 * Copyright (C) 2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.scaladsl.marshallers.sprayjson

import akka.actor.ActorSystem
import akka.http.scaladsl.marshalling.Marshal
import akka.http.scaladsl.model.MessageEntity
import akka.http.scaladsl.unmarshalling.Unmarshal
import akka.stream.ActorMaterializer

import org.scalatest._
import org.scalatest.concurrent.ScalaFutures

class SprayJsonSupportSpec extends WordSpec with Matchers with ScalaFutures {
  import SprayJsonSupport._
  import SprayJsonSupportSpec._
  import spray.json.DefaultJsonProtocol._

  implicit val exampleFormat = jsonFormat1(Example.apply)
  implicit val sys = ActorSystem("SprayJsonSupportSpec")
  implicit val mat = ActorMaterializer()
  import sys.dispatcher

  "SprayJsonSupport" should {
    "allow round trip via Marshal / Unmarshal" in {
      val init = Example("3-byte UTF-8 chars like ﾖ, ᄅ or ᐁ.")

      val js = Marshal(init).to[MessageEntity].futureValue
      val example = Unmarshal(js).to[Example].futureValue

      example should ===(init)
    }
  }

}

object SprayJsonSupportSpec {
  case class Example(username: String)
}
