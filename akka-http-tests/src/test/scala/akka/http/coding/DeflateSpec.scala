/*
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.coding

import akka.util.ByteString
import akka.http.util._
import org.scalatest.WordSpec
import akka.http.model._
import HttpMethods.POST

import java.io.ByteArrayOutputStream
import java.util.zip.{ DeflaterOutputStream, InflaterOutputStream }

class DeflateSpec extends WordSpec with CodecSpecSupport {

  "The Deflate codec" should {
    "properly encode a small string" in {
      streamInflate(ourDeflate(smallTextBytes)) should readAs(smallText)
    }
    "properly decode a small string" in {
      ourInflate(streamDeflate(smallTextBytes)) should readAs(smallText)
    }
    "properly round-trip encode/decode a small string" in {
      ourInflate(ourDeflate(smallTextBytes)) should readAs(smallText)
    }
    "properly encode a large string" in {
      streamInflate(ourDeflate(largeTextBytes)) should readAs(largeText)
    }
    "properly decode a large string" in {
      ourInflate(streamDeflate(largeTextBytes)) should readAs(largeText)
    }
    "properly round-trip encode/decode a large string" in {
      ourInflate(ourDeflate(largeTextBytes)) should readAs(largeText)
    }
    "properly round-trip encode/decode an HttpRequest" in {
      val request = HttpRequest(POST, entity = HttpEntity(largeText))
      Deflate.decode(Deflate.encode(request)) should equal(request)
    }
    "provide a better compression ratio than the standard Deflater/Inflater streams" in {
      ourDeflate(largeTextBytes).length should be < streamDeflate(largeTextBytes).length
    }
    "support chunked round-trip encoding/decoding" in {
      val chunks = largeTextBytes.grouped(512).toVector
      val comp = Deflate.newCompressor
      val decomp = Deflate.newDecompressor
      val chunks2 =
        chunks.map { chunk ⇒
          decomp.decompress(comp.compressAndFlush(chunk))
        } :+
          decomp.decompress(comp.finish())
      chunks2.join should readAs(largeText)
    }
    "works for any split in prefix + suffix" in {
      val compressed = streamDeflate(smallTextBytes)
      def tryWithPrefixOfSize(prefixSize: Int): Unit = {
        val decomp = Deflate.newDecompressor
        val prefix = compressed.take(prefixSize)
        val suffix = compressed.drop(prefixSize)

        decomp.decompress(prefix) ++ decomp.decompress(suffix) should readAs(smallText)
      }
      (0 to compressed.size).foreach(tryWithPrefixOfSize)
    }
  }

  def ourDeflate(bytes: ByteString): ByteString = Deflate.encode(bytes)
  def ourInflate(bytes: ByteString): ByteString = Deflate.decode(bytes)

  def streamDeflate(bytes: ByteString) = {
    val output = new ByteArrayOutputStream()
    val dos = new DeflaterOutputStream(output); dos.write(bytes.toArray); dos.close()
    ByteString(output.toByteArray)
  }

  def streamInflate(bytes: ByteString) = {
    val output = new ByteArrayOutputStream()
    val ios = new InflaterOutputStream(output); ios.write(bytes.toArray); ios.close()
    ByteString(output.toByteArray)
  }
}
