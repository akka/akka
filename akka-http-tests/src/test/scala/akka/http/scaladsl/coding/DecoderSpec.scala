/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.scaladsl.coding

import akka.stream.{ Attributes, FlowShape }
import akka.stream.impl.fusing.GraphStages.SimpleLinearGraphStage

import scala.concurrent.duration._
import org.scalatest.WordSpec
import akka.util.ByteString
import akka.stream.stage._
import akka.http.scaladsl.model._
import akka.http.impl.util._
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
      decoded.entity.toStrict(3.seconds).awaitResult(3.seconds) shouldEqual HttpEntity(dummyDecompress(smallText))
    }
  }

  def dummyDecompress(s: String): String = dummyDecompress(ByteString(s, "UTF8")).decodeString("UTF8")
  def dummyDecompress(bytes: ByteString): ByteString = DummyDecoder.decode(bytes).awaitResult(3.seconds)

  case object DummyDecoder extends StreamDecoder {
    val encoding = HttpEncodings.compress

    override def newDecompressorStage(maxBytesPerChunk: Int): () ⇒ GraphStage[FlowShape[ByteString, ByteString]] =
      () ⇒ new SimpleLinearGraphStage[ByteString] {
        override def createLogic(inheritedAttributes: Attributes): GraphStageLogic = new GraphStageLogic(shape) {
          setHandler(in, new InHandler {
            override def onPush(): Unit = push(out, grab(in) ++ ByteString("compressed"))
          })
          setHandler(out, new OutHandler {
            override def onPull(): Unit = pull(in)
          })
        }
      }
  }

}
