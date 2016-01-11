/*
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.coding

import akka.util.ByteString
import org.scalatest.WordSpec
import akka.http.model._
import headers._
import HttpMethods.POST

class EncoderSpec extends WordSpec with CodecSpecSupport {

  "An Encoder" should {
    "not transform the message if messageFilter returns false" in {
      val request = HttpRequest(POST, entity = HttpEntity(smallText.getBytes("UTF8")))
      DummyEncoder.encode(request) === request
    }
    "correctly transform the HttpMessage if messageFilter returns true" in {
      val request = HttpRequest(POST, entity = HttpEntity(smallText))
      val encoded = DummyEncoder.encode(request)
      encoded.headers === List(`Content-Encoding`(DummyEncoder.encoding))
      encoded.entity === HttpEntity(dummyCompress(smallText))
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
