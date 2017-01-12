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
}
