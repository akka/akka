/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.scaladsl.coding

import akka.util.ByteString
import org.scalatest.WordSpec
import akka.http.scaladsl.model._
import headers._
import HttpMethods.POST
import scala.concurrent.duration._
import akka.http.impl.util._

class EncoderSpec extends WordSpec with CodecSpecSupport {

  "An Encoder" should {
    "not transform the message if messageFilter returns false" in {
      val request = HttpRequest(POST, entity = HttpEntity(smallText.getBytes("UTF8")))
      DummyEncoder.encode(request) shouldEqual request
    }
    "correctly transform the HttpMessage if messageFilter returns true" in {
      val request = HttpRequest(POST, entity = HttpEntity(smallText))
      val encoded = DummyEncoder.encode(request)
      encoded.headers shouldEqual List(`Content-Encoding`(DummyEncoder.encoding))
      encoded.entity.toStrict(3.seconds).awaitResult(3.seconds) shouldEqual HttpEntity(dummyCompress(smallText))
    }
  }

  def dummyCompress(s: String): String = dummyCompress(ByteString(s, "UTF8")).utf8String
  def dummyCompress(bytes: ByteString): ByteString = DummyCompressor.compressAndFinish(bytes)

  case object DummyEncoder extends Encoder {
    val messageFilter = Encoder.DefaultFilter
    val encoding = HttpEncodings.compress
    def newCompressor = DummyCompressor
  }

  case object DummyCompressor extends Compressor {
    def compress(input: ByteString) = input ++ ByteString("compressed")
    def flush() = ByteString.empty
    def finish() = ByteString.empty

    def compressAndFlush(input: ByteString): ByteString = compress(input)
    def compressAndFinish(input: ByteString): ByteString = compress(input)
  }
}
