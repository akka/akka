/*
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.util

import akka.stream.stage.{ Directive, Context, StatefulStage }
import akka.util.ByteString

/**
 * A helper class for writing parsers from ByteStrings.
 *
 * FIXME: move to akka.stream.io, https://github.com/akka/akka/issues/16529
 *
 * INTERNAL API
 */
private[akka] abstract class ByteStringParserStage[Out] extends StatefulStage[ByteString, Out] {
  protected def onTruncation(ctx: Context[Out]): Directive

  /**
   * Derive a stage from [[IntermediateState]] and then call `pull(ctx)` instead of
   * `ctx.pull()` to have truncation errors reported.
   */
  abstract class IntermediateState extends State {
    override def onPull(ctx: Context[Out]): Directive = pull(ctx)
    def pull(ctx: Context[Out]): Directive =
      if (ctx.isFinishing) onTruncation(ctx)
      else ctx.pull()
  }

  /**
   * A stage that tries to read from a side-effecting [[ByteReader]]. If a buffer underrun
   * occurs the previous data is saved and the reading process is restarted from the beginning
   * once more data was received.
   *
   * As [[read]] may be called several times for the same prefix of data, make sure not to
   * manipulate any state during reading from the ByteReader.
   */
  trait ByteReadingState extends IntermediateState {
    def read(reader: ByteReader, ctx: Context[Out]): Directive

    def onPush(data: ByteString, ctx: Context[Out]): Directive =
      try {
        val reader = new ByteReader(data)
        read(reader, ctx)
      } catch {
        case ByteReader.NeedMoreData ⇒
          become(TryAgain(data, this))
          pull(ctx)
      }
  }
  case class TryAgain(previousData: ByteString, byteReadingState: ByteReadingState) extends IntermediateState {
    def onPush(data: ByteString, ctx: Context[Out]): Directive = {
      become(byteReadingState)
      byteReadingState.onPush(previousData ++ data, ctx)
    }
  }
}