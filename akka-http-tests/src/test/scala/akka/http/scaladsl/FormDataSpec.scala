/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.scaladsl

import scala.concurrent.duration._
import org.scalatest.{ BeforeAndAfterAll, Matchers, WordSpec }
import org.scalatest.concurrent.ScalaFutures
import akka.actor.ActorSystem
import akka.stream.ActorMaterializer
import akka.http.scaladsl.unmarshalling.Unmarshal
import akka.http.scaladsl.marshalling.Marshal
import akka.http.scaladsl.model._

class FormDataSpec extends WordSpec with Matchers with ScalaFutures with BeforeAndAfterAll {
  implicit val system = ActorSystem(getClass.getSimpleName)
  implicit val materializer = ActorMaterializer()
  import system.dispatcher

  val formData = FormData(Map("surname" -> "Smith", "age" -> "42"))

  "The FormData infrastructure" should {
    "properly round-trip the fields of www-urlencoded forms" in {
      Marshal(formData).to[HttpEntity]
        .flatMap(Unmarshal(_).to[FormData]).futureValue shouldEqual formData
    }

    "properly marshal www-urlencoded forms containing special chars" in {
      Marshal(FormData(Map("name" -> "Smith&Wesson"))).to[HttpEntity]
        .flatMap(Unmarshal(_).to[String]).futureValue shouldEqual "name=Smith%26Wesson"

      Marshal(FormData(Map("name" -> "Smith+Wesson; hopefully!"))).to[HttpEntity]
        .flatMap(Unmarshal(_).to[String]).futureValue shouldEqual "name=Smith%2BWesson%3B+hopefully%21"
    }
  }

  override def afterAll() = {
    system.shutdown()
    system.awaitTermination(10.seconds)
  }
}
