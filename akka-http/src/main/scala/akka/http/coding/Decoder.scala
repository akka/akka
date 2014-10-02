/*
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.coding

import java.io.{ OutputStream, ByteArrayOutputStream }

import akka.http.model._
import akka.http.util.StreamUtils
import akka.stream.{ Transformer, FlowMaterializer }
import akka.util.ByteString
import headers.HttpEncoding

trait Decoder {
  def encoding: HttpEncoding

  def decode[T <: HttpMessage](message: T)(implicit mapper: DataMapper[T], materializer: FlowMaterializer): T#Self =
    if (message.headers exists Encoder.isContentEncodingHeader)
      decodeData(message).withHeaders(message.headers filterNot Encoder.isContentEncodingHeader)
    else message.self

  def decodeData[T](t: T)(implicit mapper: DataMapper[T], materializer: FlowMaterializer): T =
    mapper.transformDataBytes(t, newDecodeTransfomer)

  def newDecompressor: Decompressor

  def newDecodeTransfomer(): Transformer[ByteString, ByteString] = {
    val decompressor = newDecompressor

    def decodeChunk(bytes: ByteString): ByteString = decompressor.decompress(bytes)
    def finish(): ByteString = ByteString.empty

    StreamUtils.byteStringTransformer(decodeChunk, finish)
  }
}

/** A stateful object representing ongoing decompression. */
abstract class Decompressor {
  /** Decompress the buffer and return decompressed data. */
  def decompress(buffer: ByteString): ByteString
}
