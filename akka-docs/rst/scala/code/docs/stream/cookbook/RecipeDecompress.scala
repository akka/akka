/**
 * Copyright (C) 2016-2017 Lightbend Inc. <http://www.lightbend.com/>
 */
package docs.stream.cookbook

import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.util.zip.GZIPOutputStream

import akka.stream.impl.io.compression.GzipCompressor
import akka.stream.scaladsl.Sink
import akka.stream.scaladsl.Source
import akka.util.ByteString

import scala.annotation.tailrec
import scala.concurrent.Await
import scala.concurrent.duration._

class RecipeDecompress extends RecipeSpec {
  def gzip(s: String): ByteString = {
    val buf = new ByteArrayOutputStream()
    val out = new GZIPOutputStream(buf)
    try out.write(s.getBytes(StandardCharsets.UTF_8)) finally out.close()
    ByteString(buf.toByteArray)
  }

  "Recipe for decompressing a Gzip stream" must {
    "work" in {
      val compressed = Source.single(gzip("Hello World"))

      //#decompress-gzip
      import akka.stream.scaladsl.Compression
      val uncompressed = compressed.via(Compression.gunzip())
        .map(_.utf8String)
      //#decompress-gzip

      Await.result(uncompressed.runWith(Sink.head), 3.seconds) should be("Hello World")
    }
  }
}
