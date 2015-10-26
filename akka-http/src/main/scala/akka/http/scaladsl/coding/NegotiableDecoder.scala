/**
  * Copyright (C) 2015 Typesafe Inc. <http://www.typesafe.com>
  */
package akka.http.scaladsl.coding

import akka.http.scaladsl.model.HttpResponse
import akka.http.scaladsl.model.headers.{HttpEncoding, HttpEncodings}
import akka.stream.scaladsl.Flow

/**
  * Provides high level decoding for HTTP client by analyzing Content-Encoding header
  * and try to find appropriate decoder. Gzip and Deflate decoders are supported.
  * In case of unknown encoding returns response as is.
  */
object NegotiableDecoder {
  private def findCodec(encoding: HttpEncoding) = encoding match {
    case HttpEncodings.gzip | HttpEncodings.`x-zip` => Gzip
    case HttpEncodings.deflate => Deflate
    case _ => NoCoding
  }

  def decode(resp: HttpResponse) = findCodec(resp.encoding).decode(resp)

  val flow = Flow[HttpResponse].map(decode)

  def flow(decoder: Decoder) = Flow[HttpResponse].map(decoder.decode[HttpResponse])

}
