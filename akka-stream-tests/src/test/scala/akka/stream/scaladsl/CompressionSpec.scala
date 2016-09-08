/**
 * Copyright (C) 2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.stream.scaladsl

import java.nio.charset.StandardCharsets

import akka.stream.impl.{ DeflateCompressor, GzipCompressor }
import akka.stream.testkit.StreamSpec
import akka.stream.testkit.scaladsl.TestSink
import akka.stream.{ ActorMaterializer, ActorMaterializerSettings }
import akka.util.ByteString

class CompressionSpec extends StreamSpec {
  val settings = ActorMaterializerSettings(system)
  implicit val materializer = ActorMaterializer(settings)

  def gzip(s: String): ByteString = new GzipCompressor().compressAndFinish(ByteString(s))

  def deflate(s: String): ByteString = new DeflateCompressor().compressAndFinish(ByteString(s))

  val data = "hello world"

  "Gzip decompression" must {
    "be able to decompress a gzipped stream" in {
      Source.single(gzip(data))
        .via(Compression.gunzip())
        .map(_.decodeString(StandardCharsets.UTF_8))
        .runWith(TestSink.probe)
        .requestNext(data)
        .expectComplete()
    }
  }

  "Deflate decompression" must {
    "be able to decompress a deflated stream" in {
      Source.single(deflate(data))
        .via(Compression.inflate())
        .map(_.decodeString(StandardCharsets.UTF_8))
        .runWith(TestSink.probe)
        .requestNext(data)
        .expectComplete()
    }
  }
}
