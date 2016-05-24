/*
 * Copyright (C) 2009-2015 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.http.scaladsl.server

import akka.NotUsed
import akka.actor.ActorSystem
import akka.http.impl.util.JavaMapping
import akka.http.scaladsl.model.{ ContentType, ContentTypes }
import akka.http.scaladsl.server.directives.FramedEntityStreamingDirectives.SourceRenderingMode
import akka.stream.scaladsl.{ Flow, Framing }
import akka.util.ByteString
import com.typesafe.config.Config

import scala.collection.immutable

/**
 * Same as [[akka.stream.scaladsl.Framing]] but additionally can express which [[ContentType]] it supports,
 * which can be used to reject routes if content type does not match used framing.
 */
abstract class FramingWithContentType extends Framing {
  def flow: Flow[ByteString, ByteString, NotUsed]
  def supported: immutable.Set[ContentType]
  def isSupported(ct: akka.http.javadsl.model.ContentType): Boolean = supported(JavaMapping.ContentType.toScala(ct))
}
object FramingWithContentType {
  def apply(framing: Flow[ByteString, ByteString, NotUsed], contentType: ContentType, moreContentTypes: ContentType*) =
    new FramingWithContentType {
      override def flow = framing

      override val supported: immutable.Set[ContentType] =
        if (moreContentTypes.isEmpty) Set(contentType)
        else Set(contentType) ++ moreContentTypes
    }
}

/**
 * Json entity streaming support, independent of used Json parsing library.
 *
 * Can be extended by various Support traits (e.g. "SprayJsonSupport"),
 * in order to provide users with both `framing` (this trait) and `marshalling`
 * (implemented by a library) by using a single trait.
 */
trait JsonEntityFramingSupport {

  /** `application/json` specific Framing implementation */
  def bracketCountingJsonFraming(maximumObjectLength: Int) = new FramingWithContentType {
    override final val flow = Flow[ByteString].via(akka.stream.scaladsl.JsonFraming.bracketCounting(maximumObjectLength))

    override val supported: immutable.Set[ContentType] = Set(ContentTypes.`application/json`)
  }
}
object JsonEntityFramingSupport extends JsonEntityFramingSupport

/**
 * Specialised rendering mode for streaming elements as JSON.
 *
 * See also: <a href="https://en.wikipedia.org/wiki/JSON_Streaming">JSON Streaming on Wikipedia</a>.
 */
trait JsonSourceRenderingMode extends SourceRenderingMode {
  override val contentType = ContentTypes.`application/json`
}

object JsonSourceRenderingMode {

  /**
   * Most compact rendering mode
   * It does not intersperse any separator between the signalled elements.
   *
   * {{{
   * {"id":42}{"id":43}{"id":44}
   * }}}
   */
  object Compact extends JsonSourceRenderingMode {
    override val start: ByteString = ByteString.empty
    override val between: ByteString = ByteString.empty
    override val end: ByteString = ByteString.empty
  }

  /**
   * Simple rendering mode, similar to [[Compact]] however interspersing elements with a `\n` character.
   *
   * {{{
   * {"id":42},{"id":43},{"id":44}
   * }}}
   */
  object CompactCommaSeparated extends JsonSourceRenderingMode {
    override val start: ByteString = ByteString.empty
    override val between: ByteString = ByteString(",")
    override val end: ByteString = ByteString.empty
  }

  /**
   * Rendering mode useful when the receiving end expects a valid JSON Array.
   * It can be useful when the client wants to detect when the stream has been successfully received in-full,
   * which it can determine by seeing the terminating `]` character.
   *
   * The framing's terminal `]` will ONLY be emitted if the stream has completed successfully,
   * in other words - the stream has been emitted completely, without errors occuring before the final element has been signaled.
   *
   * {{{
   * [{"id":42},{"id":43},{"id":44}]
   * }}}
   */
  object CompactArray extends JsonSourceRenderingMode {
    override val start: ByteString = ByteString("[")
    override val between: ByteString = ByteString(",")
    override val end: ByteString = ByteString("]")
  }

  /**
   * Recommended rendering mode.
   *
   * It is a nice balance between valid and human-readable as well as resonably small size overhead (just the `\n` between elements).
   * A good example of API's using this syntax is Twitter's Firehose (last verified at 1.1 version of that API).
   *
   * {{{
   * {"id":42}
   * {"id":43}
   * {"id":44}
   * }}}
   */
  object LineByLine extends JsonSourceRenderingMode {
    override val start: ByteString = ByteString.empty
    override val between: ByteString = ByteString("\n")
    override val end: ByteString = ByteString.empty
  }

  /**
   * Simple rendering mode interspersing each pair of elements with both `,\n`.
   * Picking the [[LineByLine]] format may be preferable, as it is slightly simpler to parse - each line being a valid json object (no need to trim the comma).
   *
   * {{{
   * {"id":42},
   * {"id":43},
   * {"id":44}
   * }}}
   */
  object LineByLineCommaSeparated extends JsonSourceRenderingMode {
    override val start: ByteString = ByteString.empty
    override val between: ByteString = ByteString(",\n")
    override val end: ByteString = ByteString.empty
  }

}

object JsonStreamingSettings {

  def apply(sys: ActorSystem): JsonStreamingSettings =
    apply(sys.settings.config.getConfig("akka.http.json-streaming"))

  def apply(c: Config): JsonStreamingSettings = {
    JsonStreamingSettings(
      c.getInt("max-object-size"),
      renderingMode(c.getString("rendering-mode")))
  }

  def renderingMode(name: String): SourceRenderingMode = name match {
    case "line-by-line"                 ⇒ JsonSourceRenderingMode.LineByLine // the default
    case "line-by-line-comma-separated" ⇒ JsonSourceRenderingMode.LineByLineCommaSeparated
    case "compact"                      ⇒ JsonSourceRenderingMode.Compact
    case "compact-comma-separated"      ⇒ JsonSourceRenderingMode.CompactCommaSeparated
    case "compact-array"                ⇒ JsonSourceRenderingMode.CompactArray
  }
}
final case class JsonStreamingSettings(
  maxObjectSize: Int,
  style:         SourceRenderingMode)
