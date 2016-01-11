/*
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.coding

import akka.stream.stage.{ Directive, Context, PushStage, Stage }
import akka.util.ByteString
import org.scalatest.WordSpec
import akka.http.model._
import headers._
import HttpMethods.POST

class DecoderSpec extends WordSpec with CodecSpecSupport {

  "A Decoder" should {
    "not transform the message if it doesn't contain a Content-Encoding header" in {
      val request = HttpRequest(POST, entity = HttpEntity(smallText))
      DummyDecoder.decode(request) shouldEqual request
    }
    "correctly transform the message if it contains a Content-Encoding header" in {
      val request = HttpRequest(POST, entity = HttpEntity(smallText), headers = List(`Content-Encoding`(DummyDecoder.encoding)))
      val decoded = DummyDecoder.decode(request)
      decoded.headers shouldEqual Nil
      decoded.entity shouldEqual HttpEntity(dummyDecompress(smallText))
    }
  }

  def dummyDecompress(s: String): String = dummyDecompress(ByteString(s, "UTF8")).decodeString("UTF8")
  def dummyDecompress(bytes: ByteString): ByteString = DummyDecoder.decode(bytes)

  case object DummyDecoder extends StreamDecoder {
    val encoding = HttpEncodings.compress

    def newDecompressorStage(maxBytesPerChunk: Int): () ⇒ Stage[ByteString, ByteString] =
      () ⇒ new PushStage[ByteString, ByteString] {
        def onPush(elem: ByteString, ctx: Context[ByteString]): Directive =
          ctx.push(elem ++ ByteString("compressed"))
      }
  }
}
