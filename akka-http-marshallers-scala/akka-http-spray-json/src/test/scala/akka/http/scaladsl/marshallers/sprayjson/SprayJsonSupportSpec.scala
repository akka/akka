/*
 * Copyright (C) 2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.scaladsl.marshallers.sprayjson

import akka.http.scaladsl.marshalling.Marshal
import akka.http.scaladsl.unmarshalling.Unmarshal

import language.postfixOps
import org.scalatest._
import spray.json.JsValue

class SprayJsonSupportSpec extends WordSpec with Matchers with ScalaFutures {
  import SprayJsonSupport._
  import SprayJsonSupportSpec._
  import spray.json.DefaultJsonProtocol._
  
  implicit val exampleFormat = jsonFormat1(Example.apply)
  
  "SprayJsonSupport" should {
    "allow round trip via Marshal / Unmarshal" in {
      val init = Example("3-byte UTF-8 chars like ﾖ, ᄅ or ᐁ.")
      
      val js = Marshal(init).to[JsValue].futureValue
      val example = Unmarshal(js).to[Example].futureValue
      
      example should === (init)
    }
  }

  
}

object SprayJsonSupportSpec {
  case class Example(username: String)
}
