/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package io.akka.integrationtest.http

import com.typesafe.config.{ ConfigFactory, Config }
import scala.concurrent.Await
import scala.concurrent.duration._
import org.scalatest.{ BeforeAndAfterAll, Matchers, WordSpec }
import akka.util.ByteString
import akka.actor.ActorSystem
import akka.http.scaladsl.model._
import akka.stream.ActorMaterializer
import akka.stream.scaladsl._
import headers._

/**
 * Integration test for external HTTP libraries that are built on top of
 * Akka HTTP core. An example of an external library is Play Framework.
 * The way these libraries use Akka HTTP core may be different to how
 * normal users would use Akka HTTP core. For example the libraries may
 * need direct access to some model objects that users who use Akka HTTP
 * directly would not require.
 *
 * This test is designed to capture the needs of these external libaries.
 * Each test gives a use case of an external library and then checks that
 * it can be fulfilled. A typical example of a use case is converting
 * between one library's HTTP model and the Akka HTTP core HTTP model.
 *
 * This test is located a different package (io.akka vs akka) in order to
 * check for any visibility issues when Akka HTTP core is used by third
 * party libraries.
 */
class HttpModelIntegrationSpec extends WordSpec with Matchers with BeforeAndAfterAll {

  val testConf: Config = ConfigFactory.parseString("""
    akka.event-handlers = ["akka.testkit.TestEventListener"]
    akka.loglevel = WARNING""")
  implicit val system = ActorSystem(getClass.getSimpleName, testConf)

  override def afterAll() = system.terminate()

  implicit val materializer = ActorMaterializer()

  "External HTTP libraries" should {

    "be able to get String headers and an Array[Byte] body out of an HttpRequest" in {

      // First create an incoming HttpRequest for the external HTTP library
      // to deal with. We're going to convert this request into the library's
      // own HTTP model.

      val request = HttpRequest(
        method = HttpMethods.POST,
        uri = Uri("/greeting"),
        headers = List(
          Host("localhost"),
          RawHeader("Origin", "null")),
        entity = HttpEntity.Default(
          contentType = ContentTypes.`application/json`,
          contentLength = 5,
          Source(List(ByteString("hello")))))

      // Our library uses a simple model of headers: a Seq[(String, String)].
      // The body is represented as an Array[Byte]. To get the headers in
      // the form we want we first need to reconstruct the original headers
      // by combining the request.headers value with the headers that are
      // implicitly contained by the request.entity. We convert all the
      // HttpHeaders by getting their name and value. We convert Content-Type
      // and Content-Length by using the toString of their values.

      val partialTextHeaders: Seq[(String, String)] = request.headers.map(h ⇒ (h.name, h.value))
      val entityTextHeaders: Seq[(String, String)] = request.entity match {
        case HttpEntity.Default(contentType, contentLength, _) ⇒
          Seq(("Content-Type", contentType.toString), ("Content-Length", contentLength.toString))
        case _ ⇒
          ???
      }
      val textHeaders: Seq[(String, String)] = entityTextHeaders ++ partialTextHeaders
      textHeaders shouldEqual Seq(
        "Content-Type" -> "application/json",
        "Content-Length" -> "5",
        "Host" -> "localhost",
        "Origin" -> "null")

      // Finally convert the body into an Array[Byte].

      val entityBytes: Array[Byte] = Await.result(request.entity.toStrict(1.second), 2.seconds).data.toArray
      entityBytes.to[Seq] shouldEqual ByteString("hello").to[Seq]
    }

    "be able to build an HttpResponse from String headers and Array[Byte] body" in {

      // External HTTP libraries (such as Play) will model HTTP differently
      // to Akka HTTP. One model uses a Seq[(String, String)] for headers and
      // an Array[Byte] for a body. The following data structures show an
      // example simple model of an HTTP response.

      val textHeaders: Seq[(String, String)] = Seq(
        "Content-Type" -> "text/plain",
        "Content-Length" -> "3",
        "X-Greeting" -> "Hello")
      val byteArrayBody: Array[Byte] = "foo".getBytes

      // Now we need to convert this model to Akka HTTP's model. To do that
      // we use Akka HTTP's HeaderParser to parse the headers, giving us a
      // List[HttpHeader].

      val parsingResults = textHeaders map { case (name, value) ⇒ HttpHeader.parse(name, value) }
      val convertedHeaders = parsingResults collect { case HttpHeader.ParsingResult.Ok(h, _) ⇒ h }
      val parseErrors = parsingResults.flatMap(_.errors)
      parseErrors shouldBe empty

      // Most of these headers are modeled by Akka HTTP as a Seq[HttpHeader],
      // but the Content-Type and Content-Length are special: their
      // values relate to the HttpEntity and so they're modeled as part of
      // the HttpEntity. These headers need to be stripped out of the main
      // Seq[Header] and dealt with separately.

      val normalHeaders = convertedHeaders.filter {
        case _: `Content-Type`   ⇒ false
        case _: `Content-Length` ⇒ false
        case _                   ⇒ true
      }
      normalHeaders.head shouldEqual RawHeader("X-Greeting", "Hello")
      normalHeaders.tail shouldEqual Nil

      val contentType = convertedHeaders.collectFirst {
        case ct: `Content-Type` ⇒ ct.contentType
      }
      contentType shouldEqual Some(ContentTypes.`text/plain(UTF-8)`)

      val contentLength = convertedHeaders.collectFirst {
        case cl: `Content-Length` ⇒ cl.length
      }
      contentLength shouldEqual Some(3)

      // We're going to model the HttpEntity as an HttpEntity.Default, so
      // convert the body into a Publisher[ByteString].

      val byteStringBody = ByteString(byteArrayBody)
      val publisherBody = Source(List(byteStringBody))

      // Finally we can create our HttpResponse.

      HttpResponse(
        entity = HttpEntity.Default(contentType.get, contentLength.get, publisherBody))
    }

    "be able to wrap HttpHeaders with custom typed headers" in {

      // This HTTP model is typed. It uses Akka HTTP types internally, but
      // no Akka HTTP types are visible to users. This typed model is a
      // model that Play Framework may eventually move to.

      object ExampleLibrary {

        trait TypedHeader {
          def name: String
          def value: String
        }

        private[ExampleLibrary] case class GenericHeader(internal: HttpHeader) extends TypedHeader {
          def name: String = internal.name
          def value: String = internal.value
        }
        private[ExampleLibrary] case class ContentTypeHeader(contentType: ContentType) extends TypedHeader {
          def name: String = "Content-Type"
          def value: String = contentType.toString
        }
        private[ExampleLibrary] case class ContentLengthHeader(length: Long) extends TypedHeader {
          def name: String = "Content-Length"
          def value: String = length.toString
        }

        // Headers can be created from strings.
        def header(name: String, value: String): TypedHeader = {
          val parsedHeader = HttpHeader.parse(name, value) match {
            case HttpHeader.ParsingResult.Ok(h, Nil) ⇒ h
            case x                                   ⇒ sys.error(s"Failed to parse: ${x.errors}")
          }
          parsedHeader match {
            case `Content-Type`(contentType) ⇒ ContentTypeHeader(contentType)
            case `Content-Length`(length)    ⇒ ContentLengthHeader(length)
            case _                           ⇒ GenericHeader(parsedHeader)
          }
        }

        // Or they can be created more directly, without parsing.
        def contentType(contentType: ContentType): TypedHeader =
          new ContentTypeHeader(contentType)
        def contentLength(length: Long): TypedHeader =
          new ContentLengthHeader(length)

      }

      // Users of ExampleLibrary should be able to create headers by
      // parsing strings
      ExampleLibrary.header("X-Y-Z", "abc")
      ExampleLibrary.header("Host", "null")
      ExampleLibrary.header("Content-Length", "3")

      // Users should also be able to create headers directly. Internally
      // we want avoid parsing when possible (for efficiency), so it's good
      // to be able to directly create a ContentType and ContentLength
      // headers.
      ExampleLibrary.contentLength(3)
      ExampleLibrary.contentType(ContentTypes.`text/plain(UTF-8)`)
    }

  }
}
