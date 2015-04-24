/*
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.coding

import akka.http.model._
import akka.http.util.StreamUtils
import akka.stream.stage.Stage
import akka.util.ByteString
import headers.HttpEncodings

/**
 * An encoder and decoder for the HTTP 'identity' encoding.
 */
object NoCoding extends Coder with StreamDecoder {
  val encoding = HttpEncodings.identity

  override def encode[T <: HttpMessage](message: T)(implicit mapper: DataMapper[T]): T#Self = message.self
  override def encodeData[T](t: T)(implicit mapper: DataMapper[T]): T = t
  override def decode[T <: HttpMessage](message: T)(implicit mapper: DataMapper[T]): T#Self = message.self
  override def decodeData[T](t: T)(implicit mapper: DataMapper[T]): T = t

  val messageFilter: HttpMessage ⇒ Boolean = _ ⇒ false

  def newCompressor = NoCodingCompressor

  def newDecompressorStage(maxBytesPerChunk: Int): () ⇒ Stage[ByteString, ByteString] =
    () ⇒ StreamUtils.limitByteChunksStage(maxBytesPerChunk)
}

object NoCodingCompressor extends Compressor {
  def compress(input: ByteString): ByteString = input
  def flush() = ByteString.empty
  def finish() = ByteString.empty

  def compressAndFlush(input: ByteString): ByteString = input
  def compressAndFinish(input: ByteString): ByteString = input
}
