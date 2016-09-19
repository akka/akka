/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.impl.engine.http2.rendering

import akka.http.impl.engine.http2.{ HeadersFrame, Http2SubStream, StreamFrameEvent }
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers._
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{ Sink, Source }
import akka.testkit.AkkaSpec
import org.scalatest.concurrent.ScalaFutures

class HttpResponseHeaderHpackCompressionSpec extends AkkaSpec with ScalaFutures {

  implicit val mat = ActorMaterializer()

  "HttpResponseHeaderHpackCompression" must {
    "compress example-c6-1 OK response status code" in {
      // example from: https://http2.github.io/http2-spec/compression.html#rfc.section.C.6.1

      /*
        :status: 302
        cache-control: private
        date: Mon, 21 Oct 2013 20:13:21 GMT
        location: https://www.example.com
       */
      val headers = List(
        `Cache-Control`(CacheDirectives.`private`(Nil)),
        Date(DateTime(2013, 10, 21, 20, 13, 21, 1, 0, false)),
        Location("https://www.example.com")
      )
      val response = HttpResponse(status = StatusCodes.Found)
        .withHeaders(headers)

      val initialFrame = runToFrameEvents(List(response)).head.initialFrame

      val expectedHeaderBlockFragment =
        """|4882 6402 5885 aec3 771a 4b61 96d0 7abe 
           |9410 54d4 44a8 2005 9504 0b81 66e0 82a6 
           |2d1b ff6e 919d 29ad 1718 63c7 8f0b 97c8 
           |e9ae 82ae 43d3
           |"""
      assertRenderedHeaderBlockFragment(initialFrame, expectedHeaderBlockFragment)
    }
    "compress two responses in same stage" in {
      val responses =
        HttpResponse(status = StatusCodes.OK) ::
          HttpResponse(status = StatusCodes.Found).withHeaders(List(
            `Cache-Control`(CacheDirectives.`private`(Nil)),
            Date(DateTime(2013, 10, 21, 20, 13, 21, 1, 0, false)),
            Location("https://www.example.com"))) ::
          Nil

      val event = runToFrameEvents(responses)

      assertRenderedHeaderBlockFragment(event(0).initialFrame, """88""")
      assertRenderedHeaderBlockFragment(event(1).initialFrame, """|4882 6402 5885 aec3 771a 4b61 96d0 7abe 
           |9410 54d4 44a8 2005 9504 0b81 66e0 82a6 
           |2d1b ff6e 919d 29ad 1718 63c7 8f0b 97c8 
           |e9ae 82ae 43d3
           |""")
    }
  }

  def runToFrameEvents(responses: List[HttpResponse]): List[Http2SubStream] = {
    Source.fromIterator(() ⇒ responses.iterator)
      .via(new HttpResponseHeaderHpackCompression)
      .runWith(Sink.seq)
      .futureValue
      .toList
  }

  def assertRenderedHeaderBlockFragment(event: StreamFrameEvent, expectedHeaderBlockFragment: String): Unit =
    event match {
      case h: HeadersFrame ⇒
        val got = h.headerBlockFragment.map(_ formatted "%02x").grouped(2).map(e ⇒ e.mkString).mkString(" ")
        val expected = expectedHeaderBlockFragment.stripMargin.replaceAll("\n", "").trim
        got should ===(expected)
    }

}
