/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.impl.engine.http2.parsing

import akka.http.impl.engine.http2.{ HeadersFrame, Http2SubStream }
import akka.http.scaladsl.model.{ HttpMethods, HttpRequest }
import akka.stream.ActorMaterializer
import akka.stream.scaladsl.{ Sink, Source }
import akka.testkit.AkkaSpec
import akka.util.ByteString
import org.scalatest.concurrent.ScalaFutures

class HttpRequestHeaderHpackDecompressionSpec extends AkkaSpec with ScalaFutures {

  implicit val mat = ActorMaterializer()

  // test data from: https://github.com/twitter/hpack/blob/master/hpack/src/test/resources/hpack
  val encodedGET = "82"
  val encodedPOST = "83"
  val encodedPathSamplePath = "040c 2f73 616d 706c 652f 7061 7468"

  "RequestHeaderHpackDecompression" must {
    "decompress spec-example-1 to right path (Uri)" in {
      val headerBlock = encodedPathSamplePath
      val headers = Map(":path" → "/sample/path")

      val bytes = parseHeaderBlock(headerBlock)
      val http2SubStreams = List(Http2SubStream(HeadersFrame(0, endStream = false, endHeaders = true, bytes), Source.empty))

      val request = runToRequest(http2SubStreams)
      request.uri.toString should ===("/sample/path")
    }
    "decompress spec-example-2 to POST HttpMethod" in {
      val headerBlock = encodedPOST

      val bytes = parseHeaderBlock(headerBlock)
      val http2SubStreams = List(Http2SubStream(HeadersFrame(0, endStream = false, endHeaders = true, bytes), Source.empty))

      val request = runToRequest(http2SubStreams)
      request.method should ===(HttpMethods.POST)
    }
    // TODO a test that has different streamIds
  }

  def runToRequest(frames: List[Http2SubStream]): HttpRequest = {
    Source.fromIterator(() ⇒ frames.iterator)
      .via(new HttpRequestHeaderHpackDecompression)
      .runWith(Sink.head)
      .futureValue
  }

  // TODO a string interpolator called `hexByteString` would be nice for this?
  def parseHeaderBlock(data: String): ByteString = {
    val bytes = data.replaceAll(" ", "").toCharArray.grouped(2).map(ch ⇒ Integer.parseInt(new String(ch), 16).toByte).toVector
    ByteString(bytes: _*)
  }
}
