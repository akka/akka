/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.http.javadsl.model

import java.util.Optional

/**
 * Represents an Http content-type. A content-type consists of a media-type and an optional charset.
 *
 * See [[ContentTypes]] for convenience access to often used values.
 */
// Has to be defined in Scala even though it's JavaDSL because of:
// https://issues.scala-lang.org/browse/SI-9621
object ContentType {

  trait Binary extends ContentType {
  }

  trait NonBinary extends ContentType {
    def charset: HttpCharset
  }

  trait WithFixedCharset extends NonBinary {
  }

  trait WithCharset extends NonBinary {
  }

}

trait ContentType {
  /**
   * The media-type of this content-type.
   */
  def mediaType: MediaType

  /**
   * True if this ContentType is non-textual.
   */
  def binary: Boolean

  /**
   * Returns the charset if this ContentType is non-binary.
   */
  def getCharsetOption: Optional[HttpCharset]
}
