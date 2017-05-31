/**
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.http.javadsl.model

/**
 * Represents an Http media-type. A media-type consists of a main-type and a sub-type.
 *
 * See [[MediaTypes]] for convenience access to often used values.
 */
// Has to be defined in Scala even though it's JavaDSL because of:
// https://issues.scala-lang.org/browse/SI-9621
object MediaType {

  trait Binary extends MediaType {
    /**
     * Turns the media type into a content type.
     */
    def toContentType: ContentType.Binary
  }

  trait NonBinary extends MediaType {
  }

  trait WithFixedCharset extends NonBinary {
    /**
     * Turns the media type into a content type with a fixed, known charset.
     */
    def toContentType: ContentType.WithFixedCharset
  }

  trait WithOpenCharset extends NonBinary {
    /**
     * Turns the media type into a content type with the given charset.
     */
    def toContentType(charset: HttpCharset): ContentType.WithCharset
    /**
     * Turns the media type into a content type without specifying a charset.
     *
     * This is generally NOT what you want, since you're hiding the actual character encoding of your content, making
     * decoding it possibly ambiguous.
     *
     * Consider using toContentType(charset: HttpCharset) instead.
     */
    def toContentTypeWithMissingCharset: ContentType.WithMissingCharset
  }

  trait Multipart extends Binary {
  }

  trait Compressibility {
    def compressible: Boolean
  }
}

trait MediaType {
  /**
   * The main-type of this media-type.
   */
  def mainType: String

  /**
   * The sub-type of this media-type.
   */
  def subType: String

  /**
   * True when this media-type is generally compressible.
   */
  def isCompressible: Boolean

  /**
   * True when this media-type is not character-based.
   */
  def binary: Boolean

  def isApplication: Boolean

  def isAudio: Boolean

  def isImage: Boolean

  def isMessage: Boolean

  def isMultipart: Boolean

  def isText: Boolean

  def isVideo: Boolean

  /**
   * Creates a media-range from this media-type.
   */
  def toRange: MediaRange

  /**
   * Creates a media-range from this media-type with a given qValue.
   */
  def toRange(qValue: Float): MediaRange
}
