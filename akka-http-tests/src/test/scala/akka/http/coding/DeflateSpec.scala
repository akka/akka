/*
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.coding

import akka.util.ByteString

import java.io.{ InputStream, OutputStream }
import java.util.zip._

class DeflateSpec extends CoderSpec {
  protected def Coder: Coder with StreamDecoder = Deflate

  protected def newDecodedInputStream(underlying: InputStream): InputStream =
    new InflaterInputStream(underlying)

  protected def newEncodedOutputStream(underlying: OutputStream): OutputStream =
    new DeflaterOutputStream(underlying)

  protected def corruptInputMessage: Option[String] = Some("invalid code -- missing end-of-block")

  override def extraTests(): Unit = {
    "throw early if header is corrupt" in {
      val ex = the[DataFormatException] thrownBy ourDecode(ByteString(0, 1, 2, 3, 4))
      ex.getMessage should equal("incorrect header check")
    }
  }
}
