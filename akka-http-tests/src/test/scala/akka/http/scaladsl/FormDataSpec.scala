/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.scaladsl

import akka.stream.ActorMaterializer
import akka.http.scaladsl.unmarshalling.Unmarshal
import akka.http.scaladsl.marshalling.Marshal
import akka.http.scaladsl.model._
import akka.testkit.AkkaSpec

class FormDataSpec extends AkkaSpec {
  implicit val materializer = ActorMaterializer()
  import system.dispatcher

  val formData = FormData(Map("surname" → "Smith", "age" → "42"))

  "The FormData infrastructure" should {
    "properly round-trip the fields of www-urlencoded forms" in {
      Marshal(formData).to[HttpEntity]
        .flatMap(Unmarshal(_).to[FormData]).futureValue shouldEqual formData
    }

    "properly marshal www-urlencoded forms containing special chars" in {
      Marshal(FormData(Map("name" → "Smith&Wesson"))).to[HttpEntity]
        .flatMap(Unmarshal(_).to[String]).futureValue shouldEqual "name=Smith%26Wesson"

      Marshal(FormData(Map("name" → "Smith+Wesson; hopefully!"))).to[HttpEntity]
        .flatMap(Unmarshal(_).to[String]).futureValue shouldEqual "name=Smith%2BWesson%3B+hopefully%21"
    }
  }

}
