/**
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.http.javadsl.model

import java.util.Optional

// Has to be defined in Scala even though it's JavaDSL because of:
// https://issues.scala-lang.org/browse/SI-9621
object ContentType {
  /** Represents a content-type which we know not to contain text (will never have a charset) */
  trait Binary extends ContentType

  /** Represents a content-type which we know to contain text, and has a specified charset. */
  trait NonBinary extends ContentType {
    def charset: HttpCharset
  }

  /**
   * Represents a content-type which we know to contain text, and would be better off having a charset,
   * but the client hasn't provided that. For example, "text/xml" without a charset parameter.
   */
  trait WithMissingCharset extends ContentType

  /** Represents a content-type which we know to contain text, where the charset always has the same predefined value. */
  trait WithFixedCharset extends NonBinary

  /** Represents a content-type which we know to contain text, and the charset is known at runtime. */
  trait WithCharset extends NonBinary
}

/**
 * Represents an Http content-type. A content-type consists of a media-type and an optional charset.
 *
 * See [[ContentTypes]] for convenience access to often used values.
 */
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
