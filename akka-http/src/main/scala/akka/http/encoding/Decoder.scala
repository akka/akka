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
import headers.HttpEncoding

trait Decoder {
  def encoding: HttpEncoding

  def decode[T <: HttpMessage](message: T, materializer: FlowMaterializer): T#Self =
    if (message.headers exists Encoder.isContentEncodingHeader)
      message.withHeadersAndEntity(
        headers = message.headers filterNot Encoder.isContentEncodingHeader,
        entity = decodeEntity(message.entity, materializer))
    else message.message

  def decodeEntity(entity: HttpEntity, materializer: FlowMaterializer): HttpEntity = throw new IllegalStateException()

  def newDecompressor: Decompressor
}

abstract class Decompressor {
  protected val output = new ResettableByteArrayOutputStream(1024)

  def decompress(buffer: Array[Byte]): Array[Byte] = {
    output.reset()
    decompress(buffer, 0)
    output.toByteArray
  }

  protected def decompress(buffer: Array[Byte], offset: Int): Int
}
