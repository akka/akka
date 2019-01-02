/*
 * Copyright (C) 2016-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.stream.scaladsl

import java.nio.charset.StandardCharsets

import akka.stream.impl.io.compression.{ DeflateCompressor, GzipCompressor }
import akka.stream.testkit.StreamSpec
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
      val source = Source.single(gzip(data))
        .via(Compression.gunzip())
        .map(_.decodeString(StandardCharsets.UTF_8))

      val res = source.runFold("")(_ + _)
      res.futureValue should ===(data)
    }
  }

  "Deflate decompression" must {
    "be able to decompress a deflated stream" in {
      val source = Source.single(deflate(data))
        .via(Compression.inflate())
        .map(_.decodeString(StandardCharsets.UTF_8))

      val res = source.runFold("")(_ + _)
      res.futureValue should ===(data)
    }
  }
}
