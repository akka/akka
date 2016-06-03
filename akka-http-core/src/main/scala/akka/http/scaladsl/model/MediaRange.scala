/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.scaladsl.model

import language.implicitConversions
import java.util
import akka.http.impl.util._
import akka.http.javadsl.{ model ⇒ jm }

sealed abstract class MediaRange extends jm.MediaRange with Renderable with WithQValue[MediaRange] {
  def value: String
  def mainType: String
  def params: Map[String, String]
  def qValue: Float
  def matches(mediaType: MediaType): Boolean
  def isApplication = false
  def isAudio = false
  def isImage = false
  def isMessage = false
  def isMultipart = false
  def isText = false
  def isVideo = false
  def isWildcard = mainType == "*"

  /**
   * Returns a copy of this instance with the params replaced by the given ones.
   * If the given map contains a "q" value the `qValue` member is (also) updated.
   */
  def withParams(params: Map[String, String]): MediaRange

  /**
   * Constructs a `ContentTypeRange` from this instance and the given charset.
   */
  def withCharsetRange(charsetRange: HttpCharsetRange): ContentTypeRange = ContentTypeRange(this, charsetRange)

  /** Java API */
  def getParams: util.Map[String, String] = {
    import collection.JavaConverters._
    params.asJava
  }
  /** Java API */
  def matches(mediaType: jm.MediaType): Boolean = {
    import akka.http.impl.util.JavaMapping.Implicits._
    matches(mediaType.asScala)
  }
}

object MediaRange {
  private[http] def splitOffQValue(params: Map[String, String], defaultQ: Float = 1.0f): (Map[String, String], Float) =
    params.get("q") match {
      case Some(x) ⇒ (params - "q") → (try x.toFloat catch { case _: NumberFormatException ⇒ 1.0f })
      case None    ⇒ params → defaultQ
    }

  private final case class Custom(mainType: String, params: Map[String, String], qValue: Float)
    extends MediaRange with ValueRenderable {
    require(0.0f <= qValue && qValue <= 1.0f, "qValue must be >= 0 and <= 1.0")
    def matches(mediaType: MediaType) = mainType == "*" || mediaType.mainType == mainType
    def withParams(params: Map[String, String]) = custom(mainType, params, qValue)
    def withQValue(qValue: Float) = if (qValue != this.qValue) custom(mainType, params, qValue) else this
    def render[R <: Rendering](r: R): r.type = {
      r ~~ mainType ~~ '/' ~~ '*'
      if (qValue < 1.0f) r ~~ ";q=" ~~ qValue
      if (params.nonEmpty) params foreach { case (k, v) ⇒ r ~~ ';' ~~ ' ' ~~ k ~~ '=' ~~# v }
      r
    }
    override def isApplication = mainType == "application"
    override def isAudio = mainType == "audio"
    override def isImage = mainType == "image"
    override def isMessage = mainType == "message"
    override def isMultipart = mainType == "multipart"
    override def isText = mainType == "text"
    override def isVideo = mainType == "video"
  }

  def custom(mainType: String, params: Map[String, String] = Map.empty, qValue: Float = 1.0f): MediaRange = {
    val (ps, q) = splitOffQValue(params, qValue)
    Custom(mainType.toRootLowerCase, ps, q)
  }

  final case class One(mediaType: MediaType, qValue: Float) extends MediaRange with ValueRenderable {
    require(0.0f <= qValue && qValue <= 1.0f, "qValue must be >= 0 and <= 1.0")
    def mainType = mediaType.mainType
    def params = mediaType.params
    override def isApplication = mediaType.isApplication
    override def isAudio = mediaType.isAudio
    override def isImage = mediaType.isImage
    override def isMessage = mediaType.isMessage
    override def isMultipart = mediaType.isMultipart
    override def isText = mediaType.isText
    override def isVideo = mediaType.isVideo
    def matches(mediaType: MediaType) =
      this.mediaType.mainType == mediaType.mainType && this.mediaType.subType == mediaType.subType
    def withParams(params: Map[String, String]) = copy(mediaType = mediaType.withParams(params))
    def withQValue(qValue: Float) = copy(qValue = qValue)
    def render[R <: Rendering](r: R): r.type = if (qValue < 1.0f) r ~~ mediaType ~~ ";q=" ~~ qValue else r ~~ mediaType
  }

  implicit def apply(mediaType: MediaType): MediaRange = apply(mediaType, 1.0f)
  def apply(mediaType: MediaType, qValue: Float = 1.0f): MediaRange = One(mediaType, qValue)
}

object MediaRanges extends ObjectRegistry[String, MediaRange] {

  sealed abstract case class PredefinedMediaRange(value: String) extends MediaRange with LazyValueBytesRenderable {
    val mainType = value takeWhile (_ != '/')
    register(mainType, this)
    def params = Map.empty
    def qValue = 1.0f
    def withParams(params: Map[String, String]) = MediaRange.custom(mainType, params)
    def withQValue(qValue: Float) = if (qValue != 1.0f) MediaRange.custom(mainType, params, qValue) else this
  }

  val `*/*` = new PredefinedMediaRange("*/*") {
    def matches(mediaType: MediaType) = true
  }
  val `*/*;q=MIN` = `*/*`.withQValue(Float.MinPositiveValue)
  val `application/*` = new PredefinedMediaRange("application/*") {
    def matches(mediaType: MediaType) = mediaType.isApplication
    override def isApplication = true
  }
  val `audio/*` = new PredefinedMediaRange("audio/*") {
    def matches(mediaType: MediaType) = mediaType.isAudio
    override def isAudio = true
  }
  val `image/*` = new PredefinedMediaRange("image/*") {
    def matches(mediaType: MediaType) = mediaType.isImage
    override def isImage = true
  }
  val `message/*` = new PredefinedMediaRange("message/*") {
    def matches(mediaType: MediaType) = mediaType.isMessage
    override def isMessage = true
  }
  val `multipart/*` = new PredefinedMediaRange("multipart/*") {
    def matches(mediaType: MediaType) = mediaType.isMultipart
    override def isMultipart = true
  }
  val `text/*` = new PredefinedMediaRange("text/*") {
    def matches(mediaType: MediaType) = mediaType.isText
    override def isText = true
  }
  val `video/*` = new PredefinedMediaRange("video/*") {
    def matches(mediaType: MediaType) = mediaType.isVideo
    override def isVideo = true
  }
}