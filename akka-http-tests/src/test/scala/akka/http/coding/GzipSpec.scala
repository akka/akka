/*
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.coding

import akka.http.util._

import java.io.{ InputStream, OutputStream }
import java.util.zip.{ ZipException, GZIPInputStream, GZIPOutputStream }

import akka.util.ByteString

class GzipSpec extends CoderSpec {
  protected def Coder: Coder with StreamDecoder = Gzip

  protected def newDecodedInputStream(underlying: InputStream): InputStream =
    new GZIPInputStream(underlying)

  protected def newEncodedOutputStream(underlying: OutputStream): OutputStream =
    new GZIPOutputStream(underlying)

  override def extraTests(): Unit = {
    "decode concatenated compressions" in {
      ourDecode(Seq(encode("Hello, "), encode("dear "), encode("User!")).join) should readAs("Hello, dear User!")
    }
    "provide a better compression ratio than the standard Gzip/Gunzip streams" in {
      ourEncode(largeTextBytes).length should be < streamEncode(largeTextBytes).length
    }
    "throw an error on truncated input" in {
      val ex = the[ZipException] thrownBy ourDecode(streamEncode(smallTextBytes).dropRight(5))
      ex.getMessage should equal("Truncated GZIP stream")
    }
    "throw early if header is corrupt" in {
      val ex = the[ZipException] thrownBy ourDecode(ByteString(0, 1, 2, 3, 4))
      ex.getMessage should equal("Not in GZIP format")
    }
  }
}
