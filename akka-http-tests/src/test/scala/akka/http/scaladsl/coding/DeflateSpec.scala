/*
 * Copyright (C) 2009-2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.http.scaladsl.coding

import akka.util.ByteString
import java.io.{ InputStream, OutputStream }
import java.util.zip._

import akka.http.scaladsl.model.HttpMethods.POST
import akka.http.scaladsl.model.{ HttpEntity, HttpRequest }
import akka.http.impl.util._
import akka.http.scaladsl.model.headers.{ HttpEncodings, `Content-Encoding` }
import akka.testkit._

import scala.concurrent.duration._
import scala.concurrent.ExecutionContext.Implicits.global

class DeflateSpec extends CoderSpec {
  protected def Coder: Coder with StreamDecoder = Deflate

  protected def newDecodedInputStream(underlying: InputStream): InputStream =
    new InflaterInputStream(underlying)

  protected def newEncodedOutputStream(underlying: OutputStream): OutputStream =
    new DeflaterOutputStream(underlying)

  override def extraTests(): Unit = {
    "throw early if header is corrupt" in {
      (the[RuntimeException] thrownBy {
        ourDecode(ByteString(0, 1, 2, 3, 4))
      }).ultimateCause should be(a[DataFormatException])
    }
    "properly round-trip encode/decode an HttpRequest using no-wrap and best compression" in {
      val request = HttpRequest(POST, entity = HttpEntity(largeText))
      Deflate.decodeMessage(encodeMessage(request, Deflater.BEST_COMPRESSION, noWrap = true)).toStrict(3.seconds.dilated)
        .awaitResult(3.seconds.dilated) should equal(request)
    }
    "properly round-trip encode/decode an HttpRequest using no-wrap and no compression" in {
      val request = HttpRequest(POST, entity = HttpEntity(largeText))
      Deflate.decodeMessage(encodeMessage(request, Deflater.NO_COMPRESSION, noWrap = true)).toStrict(3.seconds.dilated)
        .awaitResult(3.seconds.dilated) should equal(request)
    }
    "properly round-trip encode/decode an HttpRequest with wrapping and no compression" in {
      val request = HttpRequest(POST, entity = HttpEntity(largeText))
      Deflate.decodeMessage(encodeMessage(request, Deflater.NO_COMPRESSION, noWrap = false)).toStrict(3.seconds.dilated)
        .awaitResult(3.seconds.dilated) should equal(request)
    }
  }

  private def encodeMessage(request: HttpRequest, compressionLevel: Int, noWrap: Boolean): HttpRequest = {
    val deflaterWithoutWrapping = new Deflate(Encoder.DefaultFilter) {
      override def newCompressor = new DeflateCompressor {
        override lazy val deflater = new Deflater(compressionLevel, noWrap)
      }
    }
    request.transformEntityDataBytes(deflaterWithoutWrapping.encoderFlow)
      .withHeaders(`Content-Encoding`(HttpEncodings.deflate) +: request.headers)
  }
}
