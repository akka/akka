/*
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.coding

import java.io.{ OutputStream, InputStream }

class NoCodingSpec extends CoderSpec {
  protected def Coder: Coder with StreamDecoder = NoCoding

  protected def corruptInputMessage: Option[String] = None // all input data is valid

  protected def newEncodedOutputStream(underlying: OutputStream): OutputStream = underlying
  protected def newDecodedInputStream(underlying: InputStream): InputStream = underlying
}
