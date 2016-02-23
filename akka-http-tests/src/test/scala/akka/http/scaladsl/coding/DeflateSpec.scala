/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.scaladsl.coding

import akka.util.ByteString

import java.io.{ InputStream, OutputStream }
import java.util.zip._

class DeflateSpec extends CoderSpec {
  protected def Coder: Coder with StreamDecoder = Deflate

  protected def newDecodedInputStream(underlying: InputStream): InputStream =
    new InflaterInputStream(underlying)

  protected def newEncodedOutputStream(underlying: OutputStream): OutputStream =
    new DeflaterOutputStream(underlying)

  override def extraTests(): Unit = {
    "throw early if header is corrupt" in {
      (the[RuntimeException] thrownBy {
        ourDecode(ByteString(0, 1, 2, 3, 4))
      }).getCause should be(a[DataFormatException])
    }
  }
}
