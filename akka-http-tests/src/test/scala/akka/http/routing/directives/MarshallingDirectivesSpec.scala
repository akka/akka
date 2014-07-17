/*
 * Copyright © 2011-2013 the spray project <http://spray.io>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package akka.http.routing.directives

import java.io.ByteArrayInputStream
import scala.xml.{ XML, NodeSeq }
import scala.concurrent.Promise
import akka.http.unmarshalling._
import akka.http.marshalling._
import akka.http.routing._
import akka.http.model._
import headers._
import MediaTypes._
import HttpCharsets._

class MarshallingDirectivesSpec extends RoutingSpec {

  implicit def IntUnmarshaller: Unmarshaller[Int] = FIXME
  /*Unmarshaller[Int](ContentTypeRange(`text/xml`, HttpCharsets.getForKey("iso-8859-2").get), `text/html`, `application/xhtml+xml`) {
      case HttpEntity.NonEmpty(_, data) ⇒ XML.load(new ByteArrayInputStream(data.toByteArray)).text.toInt
    }*/

  implicit def IntMarshaller: Marshaller[Int] = FIXME
  //Marshaller.delegate[Int, NodeSeq](`application/xhtml+xml`, ContentType(`text/xml`, `UTF-8`))(i ⇒ <int>{ i }</int>)

  "The 'entityAs' directive" should {
    "extract an object from the requests entity using the in-scope Unmarshaller" in pendingUntilFixed {
      Put("/", <p>cool</p>) ~> {
        entity(as[NodeSeq]) { echoComplete }
      } ~> check { responseAs[String] mustEqual "<p>cool</p>" }
    }
    "return a RequestEntityExpectedRejection rejection if the request has no entity" in pendingUntilFixed {
      Put() ~> {
        entity(as[Int]) { echoComplete }
      } ~> check { rejection mustEqual RequestEntityExpectedRejection }
    }
    "return an UnsupportedRequestContentTypeRejection if no matching unmarshaller is in scope" in pendingUntilFixed {
      Put("/", HttpEntity(`text/css`, "<p>cool</p>")) ~> {
        entity(as[NodeSeq]) { echoComplete }
      } ~> check {
        rejection mustEqual UnsupportedRequestContentTypeRejection(
          "Expected 'text/xml' or 'application/xml' or 'text/html' or 'application/xhtml+xml'")
      }
    }
    "cancel UnsupportedRequestContentTypeRejections if a subsequent `contentAs` succeeds" in pendingUntilFixed {
      Put("/", HttpEntity(`text/plain`, "yeah")) ~> {
        entity(as[NodeSeq]) { _ ⇒ completeOk } ~
          entity(as[String]) { _ ⇒ validate(false, "Problem") { completeOk } }
      } ~> check { rejection mustEqual ValidationRejection("Problem") }
    }
    "extract an Option[T] from the requests HttpContent using the in-scope Unmarshaller" in pendingUntilFixed {
      Put("/", <p>cool</p>) ~> {
        entity(as[Option[NodeSeq]]) { echoComplete }
      } ~> check { responseAs[String] mustEqual "Some(<p>cool</p>)" }
    }
    "extract an Option[T] as None if the request has no entity" in pendingUntilFixed {
      Put() ~> {
        entity(as[Option[Int]]) { echoComplete }
      } ~> check { responseAs[String] mustEqual "None" }
    }
    "return an UnsupportedRequestContentTypeRejection if no matching unmarshaller is in scope (for Option[T]s)" in pendingUntilFixed {
      Put("/", HttpEntity(`text/css`, "<p>cool</p>")) ~> {
        entity(as[Option[NodeSeq]]) { echoComplete }
      } ~> check {
        rejection mustEqual UnsupportedRequestContentTypeRejection(
          "Expected 'text/xml' or 'application/xml' or 'text/html' or 'application/xhtml+xml'")
      }
    }
    "extract from a multi-unmarshaller" in pendingUntilFixed {
      case class Person(name: String)
      //import spray.json.DefaultJsonProtocol._
      //import spray.httpx.SprayJsonSupport._
      val jsonUnmarshaller: Unmarshaller[Person] = FIXME //jsonFormat1(Person)
      val xmlUnmarshaller: Unmarshaller[Person] = FIXME
      /*Unmarshaller.delegate[NodeSeq, Person](`text/xml`) { seq ⇒
        Person(seq.text)
      }*/

      implicit val unmarshaller: Unmarshaller[Person] = FIXME //Unmarshaller.oneOf[Person](jsonUnmarshaller, xmlUnmarshaller)

      val route = entity(as[Person]) { echoComplete }

      Put("/", HttpEntity(`text/xml`, "<name>Peter Xml</name>")) ~> route ~> check {
        responseAs[String] mustEqual "Person(Peter Xml)"
      }
      Put("/", HttpEntity(`application/json`, """{ "name": "Paul Json" }""")) ~> route ~> check {
        responseAs[String] mustEqual "Person(Paul Json)"
      }
      Put("/", HttpEntity(`text/plain`, """name = Sir Text }""")) ~> route ~> check {
        rejection mustEqual UnsupportedRequestContentTypeRejection("Can't unmarshal from text/plain")
      }
    }
  }

  "The 'produce' directive" should {
    "provide a completion function converting custom objects to an HttpEntity using the in-scope marshaller" in pendingUntilFixed {
      Get() ~> {
        produce(instanceOf[Int]) { prod ⇒ _ ⇒ prod(42) }
      } ~> check { body mustEqual HttpEntity(ContentType(`application/xhtml+xml`, `UTF-8`), "<int>42</int>") }
    }
    "return a UnacceptedResponseContentTypeRejection rejection if no acceptable marshaller is in scope" in pendingUntilFixed {
      Get() ~> addHeader(Accept(`text/css`)) ~> {
        produce(instanceOf[Int]) { prod ⇒ _ ⇒ prod(42) }
      } ~> check {
        rejection mustEqual UnacceptedResponseContentTypeRejection(
          Seq(ContentType(`application/xhtml+xml`), ContentType(`text/xml`, `UTF-8`)))
      }
    }
    "convert the response content to an accepted charset" in pendingUntilFixed {
      Get() ~> addHeader(`Accept-Charset`(`UTF-8`)) ~> {
        produce(instanceOf[String]) { prod ⇒ _ ⇒ prod("Hällö") }
      } ~> check { body mustEqual HttpEntity(ContentType(`text/plain`, `UTF-8`), "Hällö") }
    }
  }

  "The 'handleWith' directive" should {
    def times2(x: Int) = x * 2
    "support proper round-trip content unmarshalling/marshalling to and from a function" in pendingUntilFixed(
      Put("/", HttpEntity(`text/html`, "<int>42</int>")) ~> addHeader(Accept(`text/xml`)) ~> handleWith(times2)
        ~> check { body mustEqual HttpEntity(ContentType(`text/xml`, `UTF-8`), "<int>84</int>") })
    "result in UnsupportedRequestContentTypeRejection rejection if there is no unmarshaller supporting the requests charset" in pendingUntilFixed(
      Put("/", HttpEntity(`text/xml`, "<int>42</int>")) ~> addHeader(Accept(`text/xml`)) ~> handleWith(times2)
        ~> check { rejection mustEqual UnsupportedRequestContentTypeRejection("Expected 'text/xml; charset=ISO-8859-2' or 'text/html' or 'application/xhtml+xml'") })
    "result in an UnacceptedResponseContentTypeRejection rejection if there is no marshaller supporting the requests Accept-Charset header" in pendingUntilFixed(
      Put("/", HttpEntity(`text/html`, "<int>42</int>")) ~> addHeaders(Accept(`text/xml`), `Accept-Charset`(`UTF-16`)) ~>
        handleWith(times2) ~> check {
          rejection mustEqual UnacceptedResponseContentTypeRejection(
            Seq(ContentType(`application/xhtml+xml`), ContentType(`text/xml`, `UTF-8`)))
        })
  }

  "The marshalling infrastructure for JSON" should {
    "be tested" in pending
    /*import spray.json._
    import spray.httpx.SprayJsonSupport._
    case class Foo(name: String)
    object MyJsonProtocol extends DefaultJsonProtocol { implicit val fooFormat = jsonFormat1(Foo) }
    import MyJsonProtocol._
    val foo = Foo("Hällö")

    "render JSON with UTF-8 encoding if no `Accept-Charset` request header is present" in pendingUntilFixed {
      Get() ~> complete(foo) ~> check {
        body mustEqual HttpEntity(ContentType(`application/json`, `UTF-8`), PrettyPrinter(foo.toJson))
      }
    }
    "reject JSON rendering if an `Accept-Charset` request header requests a non-UTF-8 encoding" in pendingUntilFixed {
      Get() ~> addHeader(`Accept-Charset`(`ISO-8859-1`)) ~> complete(foo) ~> check {
        rejection mustEqual UnacceptedResponseContentTypeRejection(ContentType(`application/json`, `UTF-8`) :: Nil)
      }
    }*/
  }
}
