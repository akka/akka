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

import java.io.ByteArrayOutputStream
import akka.http.model._
import akka.http.routing.util.StreamUtils
import akka.stream.FlowMaterializer
import akka.util.ByteString
import headers._

trait Encoder {
  def encoding: HttpEncoding

  def messageFilter: HttpMessage ⇒ Boolean

  def encode[T <: HttpMessage](message: T, materializer: FlowMaterializer): T#Self =
    if (messageFilter(message) && !message.headers.exists(Encoder.isContentEncodingHeader))
      message.withHeadersAndEntity(
        headers = `Content-Encoding`(encoding) +: message.headers,
        entity = encodeEntity(message.entity, materializer))
    else message.message

  def encodeEntity(entity: HttpEntity, materializer: FlowMaterializer): HttpEntity = {
    val compressor = newCompressor

    def encodeChunk(bytes: ByteString): ByteString = ByteString(compressor.compress(bytes.toArray).flush())
    def finish(): ByteString = ByteString(compressor.finish())

    StreamUtils.mapEntityDataBytes(entity, encodeChunk, finish)(materializer)
  }

  def newCompressor: Compressor
}

object Encoder {
  import MessagePredicate._
  val DefaultFilter = (isRequest || responseStatus(_.isSuccess)) && isCompressible

  private[encoding] val isContentEncodingHeader: HttpHeader ⇒ Boolean = _.isInstanceOf[`Content-Encoding`]
}

abstract class Compressor {
  protected lazy val output = new ByteArrayOutputStream(1024)

  def compress(buffer: Array[Byte]): this.type

  def flush(): Array[Byte]

  def finish(): Array[Byte]

  protected def getBytes: Array[Byte] = {
    val bytes = output.toByteArray
    output.reset()
    bytes
  }
}