/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.scaladsl.server

import akka.NotUsed
import akka.http.scaladsl.common.{ EntityStreamingSupport, JsonEntityStreamingSupport }
import akka.http.scaladsl.marshalling.{ Marshaller, Marshalling, ToResponseMarshallable }
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers._
import akka.stream.scaladsl._
import akka.testkit.EventFilter
import akka.util.ByteString
import com.typesafe.config.{ Config, ConfigFactory }
import org.scalatest.Matchers

import scala.concurrent.Future
import scala.concurrent.duration._

class EntityStreamingSpec extends RoutingSpec {

  //#models
  case class Tweet(uid: Int, txt: String)
  case class Measurement(id: String, value: Int)
  //#models

  val tweets = List(
    Tweet(1, "#Akka rocks!"),
    Tweet(2, "Streaming is so hot right now!"),
    Tweet(3, "You cannot enter the same river twice."))
  def getTweets = Source(tweets)

  object MyJsonProtocol
    extends akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
    with spray.json.DefaultJsonProtocol {

    implicit val tweetFormat = jsonFormat2(Tweet.apply)
    implicit val measurementFormat = jsonFormat2(Measurement.apply)
  }

  "spray-json-response-streaming" in {
    import MyJsonProtocol._

    implicit val jsonStreamingSupport: JsonEntityStreamingSupport = EntityStreamingSupport.json()

    val route =
      path("tweets") {
        // [3] simply complete a request with a source of tweets:
        val tweets: Source[Tweet, NotUsed] = getTweets
        complete(tweets)
      }

    // tests ------------------------------------------------------------
    val AcceptJson = Accept(MediaRange(MediaTypes.`application/json`))
    val AcceptXml = Accept(MediaRange(MediaTypes.`text/xml`))

    Get("/tweets").withHeaders(AcceptJson) ~> route ~> check {
      responseAs[String] shouldEqual
        """[""" +
        """{"uid":1,"txt":"#Akka rocks!"},""" +
        """{"uid":2,"txt":"Streaming is so hot right now!"},""" +
        """{"uid":3,"txt":"You cannot enter the same river twice."}""" +
        """]"""
    }

    // endpoint can only marshal Json, so it will *reject* requests for application/xml:
    Get("/tweets").withHeaders(AcceptXml) ~> route ~> check {
      handled should ===(false)
      rejection should ===(UnacceptedResponseContentTypeRejection(Set(ContentTypes.`application/json`)))
    }
  }

  "line-by-line-json-response-streaming" in {
    import MyJsonProtocol._

    val start = ByteString.empty
    val sep = ByteString("\n")
    val end = ByteString.empty

    implicit val jsonStreamingSupport = EntityStreamingSupport.json()
      .withFramingRenderer(Flow[ByteString].intersperse(start, sep, end))

    val route =
      path("tweets") {
        val tweets: Source[Tweet, NotUsed] = getTweets
        complete(tweets)
      }

    // tests ------------------------------------------------------------
    val AcceptJson = Accept(MediaRange(MediaTypes.`application/json`))

    Get("/tweets").withHeaders(AcceptJson) ~> route ~> check {
      responseAs[String] shouldEqual
        """{"uid":1,"txt":"#Akka rocks!"}""" + "\n" +
        """{"uid":2,"txt":"Streaming is so hot right now!"}""" + "\n" +
        """{"uid":3,"txt":"You cannot enter the same river twice."}"""
    }
  }

  "csv-example" in {
    implicit val tweetAsCsv = Marshaller.strict[Tweet, ByteString] { t ⇒
      Marshalling.WithFixedContentType(ContentTypes.`text/csv(UTF-8)`, () ⇒ {
        val txt = t.txt.replaceAll(",", ".")
        val uid = t.uid
        ByteString(List(uid, txt).mkString(","))
      })
    }

    implicit val csvStreaming = EntityStreamingSupport.csv()

    val route =
      path("tweets") {
        val tweets: Source[Tweet, NotUsed] = getTweets
        complete(tweets)
      }

    val AcceptCsv = Accept(MediaRange(MediaTypes.`text/csv`))

    Get("/tweets").withHeaders(AcceptCsv) ~> route ~> check {
      responseAs[String] shouldEqual
        "1,#Akka rocks!" + "\n" +
        "2,Streaming is so hot right now!" + "\n" +
        "3,You cannot enter the same river twice."
    }
  }

  "spray-json-request-streaming" in {
    import MyJsonProtocol._
    implicit val jsonStreamingSupport = EntityStreamingSupport.json()

    val persistMetrics = Flow[Measurement]

    val route =
      path("metrics") {
        // [3] extract Source[Measurement, _]
        entity(asSourceOf[Measurement]) { measurements ⇒
          // alternative syntax:
          // entity(as[Source[Measurement, NotUsed]]) { measurements =>
          val measurementsSubmitted: Future[Int] =
            measurements
              .via(persistMetrics)
              .runFold(0) { (cnt, _) ⇒ cnt + 1 }

          complete {
            measurementsSubmitted.map(n ⇒ Map("msg" → s"""Total metrics received: $n"""))
          }
        }
      }

    // tests ------------------------------------------------------------
    // uploading an array or newline separated values works out of the box
    val data = HttpEntity(
      ContentTypes.`application/json`,
      """
        |{"id":"temp","value":32}
        |{"id":"temp","value":31}
        |
      """.stripMargin)

    Post("/metrics", entity = data) ~> route ~> check {
      status should ===(StatusCodes.OK)
      responseAs[String] should ===("""{"msg":"Total metrics received: 2"}""")
    }

    // the FramingWithContentType will reject any content type that it does not understand:
    val xmlData = HttpEntity(
      ContentTypes.`text/xml(UTF-8)`,
      """|<data id="temp" value="32"/>
         |<data id="temp" value="31"/>""".stripMargin)

    Post("/metrics", entity = xmlData) ~> route ~> check {
      handled should ===(false)
      rejection should ===(UnsupportedRequestContentTypeRejection(Set(ContentTypes.`application/json`)))
    }
    //#spray-json-request-streaming
  }

  "throw when attempting to render a JSON Source of raw Strings" in {
    implicit val jsonStreamingSupport: JsonEntityStreamingSupport =
      EntityStreamingSupport.json()

    val route =
      get {
        // This is wrong since we try to render JSON, but String is not a valid top level element
        // we need to provide an explicit Marshaller[String, ByteString] if we really want to render a list of strings.
        val results = Source(List("One", "Two", "Three"))
        complete(results)
      }

    try {
      Get("/") ~> route ~> check {
        EventFilter.error(pattern = "None of the available marshallings ", occurrences = 1) intercept {
          responseAs[String] // should fail
        }
      }
    } catch {
      case ex: java.lang.RuntimeException if ex.getCause != null ⇒
        val cause = ex.getCause
        cause.getClass should ===(classOf[akka.http.scaladsl.marshalling.NoStrictlyCompatibleElementMarshallingAvailableException[_]])
        cause.getMessage should include("Please provide an implicit `Marshaller[java.lang.String, HttpEntity]")
        cause.getMessage should include("that can render java.lang.String as [application/json]")
    }
  }
  "render a JSON Source of raw Strings if String => JsValue is provided" in {
    implicit val stringFormat = Marshaller[String, ByteString] { ec ⇒ s ⇒
      Future.successful {
        List(Marshalling.WithFixedContentType(ContentTypes.`application/json`, () ⇒
          ByteString("\"" + s + "\"")) // "raw string" to be rendered as json element in our stream must be enclosed by ""
        )
      }
    }

    implicit val jsonStreamingSupport =
      EntityStreamingSupport.json()

    val eventsRoute =
      get {
        val results = Source(List("One", "Two", "Three"))
        val r = ToResponseMarshallable(results).apply(Get("/"))(system.dispatcher)
        complete(r)
      }

    val `Accept:application/example` = Accept(MediaRange(MediaType.parse("application/example").right.get))
    Get("/").addHeader(`Accept:application/example`) ~> eventsRoute ~> check {
      val res = responseAs[String]

      res shouldEqual """["One","Two","Three"]"""

    }
  }

}
