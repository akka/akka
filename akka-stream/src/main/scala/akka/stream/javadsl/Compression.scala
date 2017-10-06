/**
 * Copyright (C) 2016-2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.stream.javadsl

import akka.NotUsed
import akka.stream.scaladsl
import akka.util.ByteString

object Compression {
  /**
   * Creates a Flow that decompresses gzip-compressed stream of data.
   *
   * @param maxBytesPerChunk Maximum length of the output [[ByteString]] chunk.
   */
  def gunzip(maxBytesPerChunk: Int): Flow[ByteString, ByteString, NotUsed] =
    scaladsl.Compression.gunzip(maxBytesPerChunk).asJava

  /**
   * Creates a Flow that decompresses deflate-compressed stream of data.
   *
   * @param maxBytesPerChunk Maximum length of the output [[ByteString]] chunk.
   */
  def inflate(maxBytesPerChunk: Int): Flow[ByteString, ByteString, NotUsed] =
    scaladsl.Compression.inflate(maxBytesPerChunk).asJava

  /**
   * Creates a flow that gzip-compresses a stream of ByteStrings. Note that the compressor
   * will SYNC_FLUSH after every [[ByteString]] so that it is guaranteed that every [[ByteString]]
   * coming out of the flow can be fully decompressed without waiting for additional data. This may
   * come at a compression performance cost for very small chunks.
   */
  def gzip: Flow[ByteString, ByteString, NotUsed] =
    scaladsl.Compression.gzip.asJava

  /**
   * Creates a flow that deflate-compresses a stream of ByteString. Note that the compressor
   * will SYNC_FLUSH after every [[ByteString]] so that it is guaranteed that every [[ByteString]]
   * coming out of the flow can be fully decompressed without waiting for additional data. This may
   * come at a compression performance cost for very small chunks.
   */
  def deflate: Flow[ByteString, ByteString, NotUsed] =
    scaladsl.Compression.deflate.asJava
}
