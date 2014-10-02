/*
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.coding

import akka.http.model._
import akka.stream.FlowMaterializer
import akka.util.ByteString
import headers.HttpEncodings

/**
 * An encoder and decoder for the HTTP 'identity' encoding.
 */
object NoEncoding extends Decoder with Encoder {
  val encoding = HttpEncodings.identity

  override def encode[T <: HttpMessage](message: T)(implicit mapper: DataMapper[T], materializer: FlowMaterializer): T#Self = message.self
  override def encodeData[T](t: T)(implicit mapper: DataMapper[T], materializer: FlowMaterializer): T = t
  override def decode[T <: HttpMessage](message: T)(implicit mapper: DataMapper[T], materializer: FlowMaterializer): T#Self = message.self
  override def decodeData[T](t: T)(implicit mapper: DataMapper[T], materializer: FlowMaterializer): T = t

  val messageFilter: HttpMessage ⇒ Boolean = _ ⇒ false

  def newCompressor = NoEncodingCompressor
  def newDecompressor = NoEncodingDecompressor
}

object NoEncodingCompressor extends Compressor {
  def compress(input: ByteString): ByteString = input
  def flush() = ByteString.empty
  def finish() = ByteString.empty

  def compressAndFlush(input: ByteString): ByteString = input
  def compressAndFinish(input: ByteString): ByteString = input
}
object NoEncodingDecompressor extends Decompressor {
  def decompress(input: ByteString): ByteString = input
}