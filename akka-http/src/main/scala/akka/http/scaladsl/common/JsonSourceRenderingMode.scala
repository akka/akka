/*
 * Copyright (C) 2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.scaladsl.common

import akka.http.scaladsl.model.{ ContentType, ContentTypes }
import akka.util.ByteString

/**
 * Specialised rendering mode for streaming elements as JSON.
 *
 * See also: <a href="https://en.wikipedia.org/wiki/JSON_Streaming">JSON Streaming on Wikipedia</a>.
 */
trait JsonSourceRenderingMode extends akka.http.javadsl.common.JsonSourceRenderingMode with SourceRenderingMode {
  override val contentType: ContentType.WithFixedCharset =
    ContentTypes.`application/json`
}

/**
 * Provides default JSON rendering modes.
 */
object JsonSourceRenderingModes {

  /**
   * Most compact rendering mode.
   * It does not intersperse any separator between the signalled elements.
   *
   * It is the most compact form to render JSON and can be framed properly by using [[akka.stream.javadsl.JsonFraming.bracketCounting]].
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
  object ArrayCompact extends JsonSourceRenderingMode {
    override val start: ByteString = ByteString("[")
    override val between: ByteString = ByteString(",")
    override val end: ByteString = ByteString("]")
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
   * [{"id":42},
   * {"id":43},
   * {"id":44}]
   * }}}
   */
  object ArrayLineByLine extends JsonSourceRenderingMode {
    override val start: ByteString = ByteString("[")
    override val between: ByteString = ByteString(",\n")
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
