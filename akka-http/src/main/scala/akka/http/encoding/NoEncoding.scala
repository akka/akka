/*
 * Copyright © 2011-2013 the spray project <http://spray.io>
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package akka.http.encoding

import akka.http.model._
import akka.stream.FlowMaterializer
import headers.HttpEncodings

/**
 * An encoder and decoder for the HTTP 'identity' encoding.
 */
object NoEncoding extends Decoder with Encoder {
  val encoding = HttpEncodings.identity

  override def encode[T <: HttpMessage](message: T, materializer: FlowMaterializer) = message.message
  override def encodeEntity(entity: HttpEntity, materializer: FlowMaterializer): HttpEntity = entity

  override def decode[T <: HttpMessage](message: T, materializer: FlowMaterializer) = message.message
  override def decodeEntity(entity: HttpEntity, materializer: FlowMaterializer): HttpEntity = entity

  val messageFilter: HttpMessage ⇒ Boolean = _ ⇒ false

  def newCompressor = NoEncodingCompressor
  def newDecompressor = NoEncodingDecompressor
}

class NoEncodingCompressor(private var buffer: Array[Byte]) extends Compressor {
  def compress(buffer: Array[Byte]) = { this.buffer = buffer; this }
  def flush() = buffer
  def finish() = buffer
}

object NoEncodingCompressor extends NoEncodingCompressor(akka.http.util.EmptyByteArray)

object NoEncodingDecompressor extends Decompressor {
  override def decompress(buffer: Array[Byte]) = buffer
  protected def decompress(buffer: Array[Byte], offset: Int) = throw new IllegalStateException
}