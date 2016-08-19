/*
 * Copyright (C) 2009-2015 Typesafe Inc. <http://www.typesafe.com>
 */

package docs.http.scaladsl.server.directives

import akka.NotUsed
import akka.http.scaladsl.common.{ EntityStreamingSupport, JsonEntityStreamingSupport }
import akka.http.scaladsl.marshalling._
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers.Accept
import akka.http.scaladsl.server.{ UnacceptedResponseContentTypeRejection, UnsupportedRequestContentTypeRejection }
import akka.stream.scaladsl.{ Flow, Source }
import akka.util.ByteString
import docs.http.scaladsl.server.RoutingSpec

import scala.concurrent.Future

class JsonStreamingExamplesSpec extends RoutingSpec {

  //#models
  case class Tweet(uid: Int, txt: String)
  case class Measurement(id: String, value: Int)
  //#

  val tweets = List(
    Tweet(1, "#Akka rocks!"),
    Tweet(2, "Streaming is so hot right now!"),
    Tweet(3, "You cannot enter the same river twice."))
  def getTweets = Source(tweets)

  //#formats
  object MyJsonProtocol
    extends akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport
    with spray.json.DefaultJsonProtocol {

    implicit val tweetFormat = jsonFormat2(Tweet.apply)
    implicit val measurementFormat = jsonFormat2(Measurement.apply)
  }
  //#

  "spray-json-response-streaming" in {
    // [1] import "my protocol", for marshalling Tweet objects:
    import MyJsonProtocol._

    // [2] pick a Source rendering support trait:
    // Note that the default support renders the Source as JSON Array
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

    // Configure the EntityStreamingSupport to render the elements as:
    // {"example":42}
    // {"example":43}
    // ...
    // {"example":1000}
    val start = ByteString.empty
    val sep = ByteString("\n")
    val end = ByteString.empty

    implicit val jsonStreamingSupport = EntityStreamingSupport.json()
      .withFramingRenderer(Flow[ByteString].intersperse(start, sep, end))

    val route =
      path("tweets") {
        // [3] simply complete a request with a source of tweets:
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
    // [1] provide a marshaller to ByteString
    implicit val tweetAsCsv = Marshaller.strict[Tweet, ByteString] { t =>
      Marshalling.WithFixedContentType(ContentTypes.`text/csv(UTF-8)`, () => {
        val txt = t.txt.replaceAll(",", ".")
        val uid = t.uid
        ByteString(List(uid, txt).mkString(","))
      })
    }

    // [2] enable csv streaming:
    implicit val csvStreaming = EntityStreamingSupport.csv()

    val route =
      path("tweets") {
        val tweets: Source[Tweet, NotUsed] = getTweets
        complete(tweets)
      }

    // tests ------------------------------------------------------------
    val AcceptCsv = Accept(MediaRange(MediaTypes.`text/csv`))

    Get("/tweets").withHeaders(AcceptCsv) ~> route ~> check {
      responseAs[String] shouldEqual
        "1,#Akka rocks!" + "\n" +
        "2,Streaming is so hot right now!" + "\n" +
        "3,You cannot enter the same river twice."
    }
  }

  "response-streaming-modes" in {

    {
      //#async-rendering 
      import MyJsonProtocol._
      implicit val jsonStreamingSupport: JsonEntityStreamingSupport =
        EntityStreamingSupport.json()
          .withParallelMarshalling(parallelism = 8, unordered = false)

      path("tweets") {
        val tweets: Source[Tweet, NotUsed] = getTweets
        complete(tweets)
      }
      //#
    }

    {

      //#async-unordered-rendering
      import MyJsonProtocol._
      implicit val jsonStreamingSupport: JsonEntityStreamingSupport =
        EntityStreamingSupport.json()
          .withParallelMarshalling(parallelism = 8, unordered = true)

      path("tweets" / "unordered") {
        val tweets: Source[Tweet, NotUsed] = getTweets
        complete(tweets)
      }
      //#
    }
  }

  "spray-json-request-streaming" in {
    // [1] import "my protocol", for unmarshalling Measurement objects:
    import MyJsonProtocol._

    // [2] enable Json Streaming
    implicit val jsonStreamingSupport = EntityStreamingSupport.json()

    // prepare your persisting logic here
    val persistMetrics = Flow[Measurement]

    val route =
      path("metrics") {
        // [3] extract Source[Measurement, _]
        entity(asSourceOf[Measurement]) { measurements =>
          // alternative syntax:
          // entity(as[Source[Measurement, NotUsed]]) { measurements =>
          val measurementsSubmitted: Future[Int] =
            measurements
              .via(persistMetrics)
              .runFold(0) { (cnt, _) => cnt + 1 }

          complete {
            measurementsSubmitted.map(n => Map("msg" -> s"""Total metrics received: $n"""))
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
  }

}
