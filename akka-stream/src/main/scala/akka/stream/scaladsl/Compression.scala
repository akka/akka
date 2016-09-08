/**
 * Copyright (C) 2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.stream.scaladsl

import akka.NotUsed
import akka.stream.impl.{ DeflateDecompressor, DeflateDecompressorBase, GzipDecompressor }
import akka.util.ByteString

object Compression {
  /**
   * Creates a Flow that decompresses gzip-compressed stream of data.
   *
   * @param maxBytesPerChunk Maximum length of the output [[ByteString]] chunk.
   */
  def gunzip(maxBytesPerChunk: Int = DeflateDecompressorBase.MaxBytesPerChunkDefault): Flow[ByteString, ByteString, NotUsed] =
    Flow[ByteString].via(new GzipDecompressor(maxBytesPerChunk))
      .named("gunzip")

  /**
   * Creates a Flow that decompresses deflate-compressed stream of data.
   *
   * @param maxBytesPerChunk Maximum length of the output [[ByteString]] chunk.
   */
  def inflate(maxBytesPerChunk: Int = DeflateDecompressorBase.MaxBytesPerChunkDefault): Flow[ByteString, ByteString, NotUsed] =
    Flow[ByteString].via(new DeflateDecompressor(maxBytesPerChunk))
      .named("inflate")
}
