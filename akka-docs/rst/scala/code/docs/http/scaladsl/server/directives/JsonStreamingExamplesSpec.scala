/*
 * Copyright (C) 2009-2015 Typesafe Inc. <http://www.typesafe.com>
 */

package docs.http.scaladsl.server.directives

import akka.NotUsed
import akka.http.scaladsl.common.{ FramingWithContentType, JsonSourceRenderingModes }
import akka.http.scaladsl.marshalling.ToResponseMarshallable
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers.Accept
import akka.http.scaladsl.server.{ UnacceptedResponseContentTypeRejection, UnsupportedRequestContentTypeRejection }
import akka.stream.scaladsl.{ Flow, Source }
import docs.http.scaladsl.server.RoutingSpec

import scala.concurrent.Future

class JsonStreamingExamplesSpec extends RoutingSpec {

  //#models
  case class Tweet(uid: Int, txt: String)
  case class Measurement(id: String, value: Int)
  //#

  def getTweets() =
    Source(List(
      Tweet(1, "#Akka rocks!"),
      Tweet(2, "Streaming is so hot right now!"),
      Tweet(3, "You cannot enter the same river twice.")))

  //#formats
  object MyJsonProtocol extends spray.json.DefaultJsonProtocol {
    implicit val tweetFormat = jsonFormat2(Tweet.apply)
    implicit val measurementFormat = jsonFormat2(Measurement.apply)
  }
  //#

  "spray-json-response-streaming" in {
    // [1] import generic spray-json marshallers support:
    import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._

    // [2] import "my protocol", for marshalling Tweet objects:
    import MyJsonProtocol._

    // [3] pick json rendering mode:
    // HINT: if you extend `akka.http.scaladsl.server.EntityStreamingSupport` 
    //       it'll guide you to do so via abstract defs
    implicit val jsonRenderingMode = JsonSourceRenderingModes.LineByLine

    val route =
      path("tweets") {
        val tweets: Source[Tweet, NotUsed] = getTweets()
        complete(tweets)
      }

    // tests:
    val AcceptJson = Accept(MediaRange(MediaTypes.`application/json`))
    val AcceptXml = Accept(MediaRange(MediaTypes.`text/xml`))

    Get("/tweets").withHeaders(AcceptJson) ~> route ~> check {
      responseAs[String] shouldEqual
        """{"uid":1,"txt":"#Akka rocks!"}""" + "\n" +
        """{"uid":2,"txt":"Streaming is so hot right now!"}""" + "\n" +
        """{"uid":3,"txt":"You cannot enter the same river twice."}"""
    }

    // endpoint can only marshal Json, so it will *reject* requests for application/xml:
    Get("/tweets").withHeaders(AcceptXml) ~> route ~> check {
      handled should ===(false)
      rejection should ===(UnacceptedResponseContentTypeRejection(Set(ContentTypes.`application/json`)))
    }
  }

  "response-streaming-modes" in {
    import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._
    import MyJsonProtocol._
    implicit val jsonRenderingMode = JsonSourceRenderingModes.LineByLine

    //#async-rendering
    path("tweets") {
      val tweets: Source[Tweet, NotUsed] = getTweets()
      complete(tweets.renderAsync(parallelism = 8))
    }
    //#

    //#async-unordered-rendering
    path("tweets" / "unordered") {
      val tweets: Source[Tweet, NotUsed] = getTweets()
      complete(tweets.renderAsyncUnordered(parallelism = 8))
    }
    //#
  }

  "spray-json-request-streaming" in {
    // [1] import generic spray-json (un)marshallers support:
    import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport._

    // [1.1] import framing mode
    import akka.http.scaladsl.server.EntityStreamingSupport
    implicit val jsonFramingMode: FramingWithContentType =
      EntityStreamingSupport.bracketCountingJsonFraming(Int.MaxValue)

    // [2] import "my protocol", for unmarshalling Measurement objects:
    import MyJsonProtocol._

    // [3] prepare your persisting logic here
    val persistMetrics = Flow[Measurement]

    val route =
      path("metrics") {
        // [4] extract Source[Measurement, _]
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

    // tests:
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
