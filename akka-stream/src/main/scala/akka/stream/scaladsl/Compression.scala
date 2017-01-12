/**
 * Copyright (C) 2016-2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.stream.scaladsl

import akka.NotUsed
import akka.stream.impl.io.compression._
import akka.util.ByteString

object Compression {
  final val MaxBytesPerChunkDefault = 64 * 1024

  /**
   * Creates a flow that gzip-compresses a stream of ByteStrings. Note that the compressor
   * will SYNC_FLUSH after every [[ByteString]] so that it is guaranteed that every [[ByteString]]
   * coming out of the flow can be fully decompressed without waiting for additional data. This may
   * come at a compression performance cost for very small chunks.
   *
   * FIXME: should compression level / strategy / flush mode be configurable? See https://github.com/akka/akka/issues/21849
   */
  def gzip: Flow[ByteString, ByteString, NotUsed] =
    CompressionUtils.compressorFlow(() ⇒ new GzipCompressor)

  /**
   * Creates a Flow that decompresses a gzip-compressed stream of data.
   *
   * @param maxBytesPerChunk Maximum length of an output [[ByteString]] chunk.
   */
  def gunzip(maxBytesPerChunk: Int = MaxBytesPerChunkDefault): Flow[ByteString, ByteString, NotUsed] =
    Flow[ByteString].via(new GzipDecompressor(maxBytesPerChunk))
      .named("gunzip")

  /**
   * Creates a flow that deflate-compresses a stream of ByteString. Note that the compressor
   * will SYNC_FLUSH after every [[ByteString]] so that it is guaranteed that every [[ByteString]]
   * coming out of the flow can be fully decompressed without waiting for additional data. This may
   * come at a compression performance cost for very small chunks.
   *
   * FIXME: should compression level / strategy / flush mode be configurable? See https://github.com/akka/akka/issues/21849
   */
  def deflate: Flow[ByteString, ByteString, NotUsed] =
    CompressionUtils.compressorFlow(() ⇒ new DeflateCompressor)

  /**
   * Creates a Flow that decompresses a deflate-compressed stream of data.
   *
   * @param maxBytesPerChunk Maximum length of an output [[ByteString]] chunk.
   */
  def inflate(maxBytesPerChunk: Int = MaxBytesPerChunkDefault): Flow[ByteString, ByteString, NotUsed] =
    Flow[ByteString].via(new DeflateDecompressor(maxBytesPerChunk))
      .named("inflate")
}
