/*
 * Copyright (C) 2009-2015 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.impl.engine.ws

import akka.stream.scaladsl.{ Keep, BidiFlow, Flow }
import akka.stream.stage.{ SyncDirective, Context, StageState, StatefulStage }

import scala.util.Random

/**
 * Implements Websocket Frame masking.
 *
 * INTERNAL API
 */
private[http] object Masking {
  def apply(serverSide: Boolean, maskRandom: () ⇒ Random): BidiFlow[ /* net in */ FrameEvent, /* app out */ FrameEvent, /* app in */ FrameEvent, /* net out */ FrameEvent, Unit] =
    BidiFlow.wrap(unmaskIf(serverSide), maskIf(!serverSide, maskRandom))(Keep.none)

  def maskIf(condition: Boolean, maskRandom: () ⇒ Random): Flow[FrameEvent, FrameEvent, Unit] =
    if (condition) Flow[FrameEvent].transform(() ⇒ new Masking(maskRandom())) // new random per materialization
    else Flow[FrameEvent]
  def unmaskIf(condition: Boolean): Flow[FrameEvent, FrameEvent, Unit] =
    if (condition) Flow[FrameEvent].transform(() ⇒ new Unmasking())
    else Flow[FrameEvent]

  class Masking(random: Random) extends Masker {
    def extractMask(header: FrameHeader): Int = random.nextInt()
    def setNewMask(header: FrameHeader, mask: Int): FrameHeader = {
      if (header.mask.isDefined) throw new ProtocolException("Frame mustn't already be masked")
      header.copy(mask = Some(mask))
    }
  }
  class Unmasking extends Masker {
    def extractMask(header: FrameHeader): Int = header.mask match {
      case Some(mask) ⇒ mask
      case None       ⇒ throw new ProtocolException("Frame wasn't masked")
    }
    def setNewMask(header: FrameHeader, mask: Int): FrameHeader = header.copy(mask = None)
  }

  /** Implements both masking and unmasking which is mostly symmetric (because of XOR) */
  abstract class Masker extends StatefulStage[FrameEvent, FrameEvent] {
    def extractMask(header: FrameHeader): Int
    def setNewMask(header: FrameHeader, mask: Int): FrameHeader

    def initial: State = Idle

    object Idle extends State {
      def onPush(part: FrameEvent, ctx: Context[FrameEvent]): SyncDirective =
        part match {
          case start @ FrameStart(header, data) ⇒
            if (header.length == 0) ctx.push(part)
            else {
              val mask = extractMask(header)
              become(new Running(mask))
              current.onPush(start.copy(header = setNewMask(header, mask)), ctx)
            }
        }
    }
    class Running(initialMask: Int) extends State {
      var mask = initialMask

      def onPush(part: FrameEvent, ctx: Context[FrameEvent]): SyncDirective = {
        if (part.lastPart) become(Idle)

        val (masked, newMask) = FrameEventParser.mask(part.data, mask)
        mask = newMask
        ctx.push(part.withData(data = masked))
      }
    }
  }
}
