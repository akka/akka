/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.scaladsl.server
package directives

import scala.xml.NodeSeq
import org.scalatest.Inside
import akka.http.scaladsl.marshallers.xml.ScalaXmlSupport
import akka.http.scaladsl.unmarshalling._
import akka.http.scaladsl.marshalling._
import akka.http.scaladsl.model._
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
import spray.json.DefaultJsonProtocol._
import MediaTypes._
import HttpCharsets._
import headers._
import org.xml.sax.SAXParseException

import scala.concurrent.Future
import scala.util.{ Failure, Try }

class MarshallingDirectivesSpec extends RoutingSpec with Inside {
  import ScalaXmlSupport._

  private val iso88592 = HttpCharsets.getForKey("iso-8859-2").get
  implicit val IntUnmarshaller: FromEntityUnmarshaller[Int] =
    nodeSeqUnmarshaller(ContentTypeRange(`text/xml`, iso88592), `text/html`) map {
      case NodeSeq.Empty ⇒ throw Unmarshaller.NoContentException
      case x             ⇒ { val i = x.text.toInt; require(i >= 0); i }
    }
  implicit val TryIntUnmarshaller: FromEntityUnmarshaller[Try[Int]] =
    IntUnmarshaller map {
      Try(_).recoverWith {
        case e: IllegalArgumentException ⇒ Failure(RejectionError(ValidationRejection(e.getMessage, Option(e))))
      }
    }

  val `text/xxml` = MediaType.customWithFixedCharset("text", "xxml", `UTF-8`)
  implicit val IntMarshaller: ToEntityMarshaller[Int] =
    Marshaller.oneOf[MediaType.NonBinary, Int, MessageEntity](`application/xhtml+xml`, `text/xxml`) { mediaType ⇒
      nodeSeqMarshaller(mediaType).wrap(mediaType) { (i: Int) ⇒ <int>{ i }</int> }
    }

  "The 'entityAs' directive" should {
    "extract an object from the requests entity using the in-scope Unmarshaller" in {
      Put("/", <p>cool</p>) ~> {
        entity(as[NodeSeq]) { echoComplete }
      } ~> check { responseAs[String] shouldEqual "<p>cool</p>" }
    }
    "return a RequestEntityExpectedRejection rejection if the request has no entity" in {
      Put() ~> {
        entity(as[Int]) { echoComplete }
      } ~> check { rejection shouldEqual RequestEntityExpectedRejection }
    }
    "return an UnsupportedRequestContentTypeRejection if no matching unmarshaller is in scope" in {
      Put("/", HttpEntity(`text/css` withCharset `UTF-8`, "<p>cool</p>")) ~> {
        entity(as[NodeSeq]) { echoComplete }
      } ~> check {
        rejection shouldEqual UnsupportedRequestContentTypeRejection(Set(`text/xml`, `application/xml`, `text/html`, `application/xhtml+xml`))
      }
      Put("/", HttpEntity(ContentType(`text/xml`, `UTF-16`), "<int>26</int>")) ~> {
        entity(as[Int]) { echoComplete }
      } ~> check {
        rejection shouldEqual UnsupportedRequestContentTypeRejection(Set(ContentTypeRange(`text/xml`, iso88592), `text/html`))
      }
    }
    "cancel UnsupportedRequestContentTypeRejections if a subsequent `entity` directive succeeds" in {
      Put("/", HttpEntity(ContentTypes.`text/plain(UTF-8)`, "yeah")) ~> {
        entity(as[NodeSeq]) { _ ⇒ completeOk } ~
          entity(as[String]) { _ ⇒ validate(false, "Problem") { completeOk } }
      } ~> check { rejection shouldEqual ValidationRejection("Problem") }
    }
    "return a ValidationRejection if the request entity is semantically invalid (IllegalArgumentException)" in {
      Put("/", HttpEntity(ContentType(`text/xml`, iso88592), "<int>-3</int>")) ~> {
        entity(as[Int]) { _ ⇒ completeOk }
      } ~> check {
        inside(rejection) {
          case ValidationRejection("requirement failed", Some(_: IllegalArgumentException)) ⇒
        }
      }
    }
    "unwrap a RejectionError and return its exception" in {
      Put("/", HttpEntity(ContentType(`text/xml`, iso88592), "<int>-3</int>")) ~> {
        entity(as[Try[Int]]) { _ ⇒ completeOk }
      } ~> check {
        inside(rejection) {
          case ValidationRejection("requirement failed", Some(_: IllegalArgumentException)) ⇒
        }
      }
    }
    "return a MalformedRequestContentRejection if unmarshalling failed due to a not further classified error" in {
      Put("/", HttpEntity(ContentTypes.`text/xml(UTF-8)`, "<foo attr='illegal xml'")) ~> {
        entity(as[NodeSeq]) { _ ⇒ completeOk }
      } ~> check {
        inside(rejection) {
          case MalformedRequestContentRejection(
            "XML document structures must start and end within the same entity.", _: SAXParseException) ⇒
        }
      }
    }
    "extract an Option[T] from the requests entity using the in-scope Unmarshaller" in {
      Put("/", <p>cool</p>) ~> {
        entity(as[Option[NodeSeq]]) { echoComplete }
      } ~> check { responseAs[String] shouldEqual "Some(<p>cool</p>)" }
    }
    "extract an Option[T] as None if the request has no entity" in {
      Put() ~> {
        entity(as[Option[Int]]) { echoComplete }
      } ~> check { responseAs[String] shouldEqual "None" }
    }
    "return an UnsupportedRequestContentTypeRejection if no matching unmarshaller is in scope (for Option[T]s)" in {
      Put("/", HttpEntity(`text/css` withCharset `UTF-8`, "<p>cool</p>")) ~> {
        entity(as[Option[NodeSeq]]) { echoComplete }
      } ~> check {
        rejection shouldEqual UnsupportedRequestContentTypeRejection(Set(`text/xml`, `application/xml`, `text/html`, `application/xhtml+xml`))
      }
    }
    "properly extract with a super-unmarshaller" in {
      case class Person(name: String)
      val jsonUnmarshaller: FromEntityUnmarshaller[Person] = jsonFormat1(Person)
      val xmlUnmarshaller: FromEntityUnmarshaller[Person] =
        ScalaXmlSupport.nodeSeqUnmarshaller(`text/xml`).map(seq ⇒ Person(seq.text))

      implicit val unmarshaller = Unmarshaller.firstOf(jsonUnmarshaller, xmlUnmarshaller)

      val route = entity(as[Person]) { echoComplete }

      Put("/", HttpEntity(ContentTypes.`text/xml(UTF-8)`, "<name>Peter Xml</name>")) ~> route ~> check {
        responseAs[String] shouldEqual "Person(Peter Xml)"
      }
      Put("/", HttpEntity(`application/json`, """{ "name": "Paul Json" }""")) ~> route ~> check {
        responseAs[String] shouldEqual "Person(Paul Json)"
      }
      Put("/", HttpEntity(ContentTypes.`text/plain(UTF-8)`, """name = Sir Text }""")) ~> route ~> check {
        rejection shouldEqual UnsupportedRequestContentTypeRejection(Set(`application/json`, `text/xml`))
      }
    }
  }

  "The 'complete' directive" should {
    "properly marshal failed Futures even in a NoContent context (#589)" in {
      def doSomethingWhichReturnsAFailedFuture(): Future[String] =
        Future.failed(new Exception("oops"))

      val route =
        path("589") {
          complete(StatusCodes.NoContent, doSomethingWhichReturnsAFailedFuture())
        }

      Get("/589") ~> route ~> check {
        response.status shouldEqual StatusCodes.InternalServerError
        responseAs[String] shouldEqual "There was an internal server error."
      }
    }
    "properly marshal successful Futures even in a NoContent context (#589)" in {
      def doSomethingWhichReturnsASuccessfulFuture(): Future[String] =
        Future.successful("hello!")

      val route =
        path("589") {
          complete(StatusCodes.NoContent, doSomethingWhichReturnsASuccessfulFuture())
        }

      Get("/589") ~> route ~> check {
        response.status shouldEqual StatusCodes.NoContent
        responseAs[String] shouldEqual "" // please note that this would be "hello!" for StatusCodes.OK case
      }
    }
  }

  "The 'completeWith' directive" should {
    "provide a completion function converting custom objects to an HttpEntity using the in-scope marshaller" in {
      Get() ~> completeWith(instanceOf[Int]) { prod ⇒ prod(42) } ~> check {
        responseEntity shouldEqual HttpEntity(ContentType(`application/xhtml+xml`, `UTF-8`), "<int>42</int>")
      }
    }
    "return a UnacceptedResponseContentTypeRejection rejection if no acceptable marshaller is in scope" in {
      Get() ~> Accept(`text/css`) ~> completeWith(instanceOf[Int]) { prod ⇒ prod(42) } ~> check {
        rejection shouldEqual UnacceptedResponseContentTypeRejection(Set(`application/xhtml+xml`, `text/xxml`))
      }
    }
    "convert the response content to an accepted charset" in {
      Get() ~> `Accept-Charset`(`UTF-8`) ~> completeWith(instanceOf[String]) { prod ⇒ prod("Hällö") } ~> check {
        responseEntity shouldEqual HttpEntity(ContentType(`text/plain`, `UTF-8`), "Hällö")
      }
    }
  }

  "The 'handleWith' directive" should {
    def times2(x: Int) = x * 2

    "support proper round-trip content unmarshalling/marshalling to and from a function" in (
      Put("/", HttpEntity(ContentTypes.`text/html(UTF-8)`, "<int>42</int>")) ~> Accept(`text/xxml`) ~> handleWith(times2)
      ~> check { responseEntity shouldEqual HttpEntity(`text/xxml`, "<int>84</int>") })

    "result in UnsupportedRequestContentTypeRejection rejection if there is no unmarshaller supporting the requests charset" in (
      Put("/", HttpEntity(ContentTypes.`text/xml(UTF-8)`, "<int>42</int>")) ~> Accept(`text/xml`) ~> handleWith(times2)
      ~> check {
        rejection shouldEqual UnsupportedRequestContentTypeRejection(Set(ContentTypeRange(`text/xml`, iso88592), `text/html`))
      })

    "result in an UnacceptedResponseContentTypeRejection rejection if there is no marshaller supporting the requests Accept-Charset header" in (
      Put("/", HttpEntity(ContentTypes.`text/html(UTF-8)`, "<int>42</int>")) ~> addHeaders(Accept(`text/xxml`), `Accept-Charset`(`UTF-16`)) ~>
      handleWith(times2) ~> check {
        rejection shouldEqual UnacceptedResponseContentTypeRejection(Set(`application/xhtml+xml`, `text/xxml`))
      })
  }

  "The marshalling infrastructure for JSON" should {
    import spray.json._
    case class Foo(name: String)
    implicit val fooFormat = jsonFormat1(Foo)
    val foo = Foo("Hällö")

    "render JSON with UTF-8 encoding if no `Accept-Charset` request header is present" in {
      Get() ~> complete(foo) ~> check {
        responseEntity shouldEqual HttpEntity(`application/json`, foo.toJson.compactPrint)
      }
    }
    "reject JSON rendering if an `Accept-Charset` request header requests a non-UTF-8 encoding" in {
      Get() ~> `Accept-Charset`(`ISO-8859-1`) ~> complete(foo) ~> check {
        rejection shouldEqual UnacceptedResponseContentTypeRejection(Set(ContentType(`application/json`)))
      }
    }
  }
}
