/*
 * Copyright (C) 2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.javadsl.common

import akka.http.javadsl.model.{ ContentType, ContentTypes }

/**
 * Specialised rendering mode for streaming elements as JSON.
 *
 * See also: <a href="https://en.wikipedia.org/wiki/JSON_Streaming">JSON Streaming on Wikipedia</a>.
 *
 * See [[JsonSourceRenderingModes]] for commonly used pre-defined rendering modes.
 */
trait JsonSourceRenderingMode extends SourceRenderingMode {
  override val contentType: ContentType.WithFixedCharset =
    ContentTypes.APPLICATION_JSON
}

/**
 * Provides default JSON rendering modes.
 */
object JsonSourceRenderingModes {

  /**
   * Most compact rendering mode.
   * It does not intersperse any separator between the signalled elements.
   *
   * It can be used with [[akka.stream.javadsl.JsonFraming.bracketCounting]].
   *
   * {{{
   * {"id":42}{"id":43}{"id":44}
   * }}}
   */
  val compact = akka.http.scaladsl.common.JsonSourceRenderingModes.Compact

  /**
   * Simple rendering mode, similar to [[compact]] however interspersing elements with a `\n` character.
   *
   * {{{
   * {"id":42},{"id":43},{"id":44}
   * }}}
   */
  val compactCommaSeparated = akka.http.scaladsl.common.JsonSourceRenderingModes.CompactCommaSeparated

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
  val arrayCompact = akka.http.scaladsl.common.JsonSourceRenderingModes.ArrayCompact

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
  val arrayLineByLine = akka.http.scaladsl.common.JsonSourceRenderingModes.ArrayLineByLine

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
  val lineByLine = akka.http.scaladsl.common.JsonSourceRenderingModes.LineByLine

  /**
   * Simple rendering mode interspersing each pair of elements with both `,\n`.
   * Picking the [[lineByLine]] format may be preferable, as it is slightly simpler to parse - each line being a valid json object (no need to trim the comma).
   *
   * {{{
   * {"id":42},
   * {"id":43},
   * {"id":44}
   * }}}
   */
  val lineByLineCommaSeparated = akka.http.scaladsl.common.JsonSourceRenderingModes.LineByLineCommaSeparated

}
