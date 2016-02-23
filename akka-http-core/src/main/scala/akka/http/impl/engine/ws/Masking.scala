/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.impl.engine.ws

import java.util.Random

import akka.NotUsed
import akka.stream.scaladsl.{ Keep, BidiFlow, Flow }
import akka.stream.stage.{ SyncDirective, Context, StatefulStage }

/**
 * Implements WebSocket Frame masking.
 *
 * INTERNAL API
 */
private[http] object Masking {
  def apply(serverSide: Boolean, maskRandom: () ⇒ Random): BidiFlow[ /* net in */ FrameEvent, /* app out */ FrameEventOrError, /* app in */ FrameEvent, /* net out */ FrameEvent, NotUsed] =
    BidiFlow.fromFlowsMat(unmaskIf(serverSide), maskIf(!serverSide, maskRandom))(Keep.none)

  def maskIf(condition: Boolean, maskRandom: () ⇒ Random): Flow[FrameEvent, FrameEvent, NotUsed] =
    if (condition)
      Flow[FrameEvent]
        .transform(() ⇒ new Masking(maskRandom())) // new random per materialization
        .map {
          case f: FrameEvent  ⇒ f
          case FrameError(ex) ⇒ throw ex
        }
    else Flow[FrameEvent]
  def unmaskIf(condition: Boolean): Flow[FrameEvent, FrameEventOrError, NotUsed] =
    if (condition) Flow[FrameEvent].transform(() ⇒ new Unmasking())
    else Flow[FrameEvent]

  private class Masking(random: Random) extends Masker {
    def extractMask(header: FrameHeader): Int = random.nextInt()
    def setNewMask(header: FrameHeader, mask: Int): FrameHeader = {
      if (header.mask.isDefined) throw new ProtocolException("Frame mustn't already be masked")
      header.copy(mask = Some(mask))
    }
    override def toString: String = s"Masking($random)"
  }
  private class Unmasking extends Masker {
    def extractMask(header: FrameHeader): Int = header.mask match {
      case Some(mask) ⇒ mask
      case None       ⇒ throw new ProtocolException("Frame wasn't masked")
    }
    def setNewMask(header: FrameHeader, mask: Int): FrameHeader = header.copy(mask = None)
    override def toString: String = "Unmasking"
  }

  /** Implements both masking and unmasking which is mostly symmetric (because of XOR) */
  private abstract class Masker extends StatefulStage[FrameEvent, FrameEventOrError] {
    def extractMask(header: FrameHeader): Int
    def setNewMask(header: FrameHeader, mask: Int): FrameHeader

    def initial: State = Idle

    private object Idle extends State {
      def onPush(part: FrameEvent, ctx: Context[FrameEventOrError]): SyncDirective =
        part match {
          case start @ FrameStart(header, data) ⇒
            try {
              val mask = extractMask(header)
              become(new Running(mask))
              current.onPush(start.copy(header = setNewMask(header, mask)), ctx)
            } catch {
              case p: ProtocolException ⇒
                become(Done)
                ctx.push(FrameError(p))
            }
          case _: FrameData ⇒
            ctx.fail(new IllegalStateException("unexpected FrameData (need FrameStart first)"))
        }
    }
    private class Running(initialMask: Int) extends State {
      var mask = initialMask

      def onPush(part: FrameEvent, ctx: Context[FrameEventOrError]): SyncDirective = {
        if (part.lastPart) become(Idle)

        val (masked, newMask) = FrameEventParser.mask(part.data, mask)
        mask = newMask
        ctx.push(part.withData(data = masked))
      }
    }
    private object Done extends State {
      def onPush(part: FrameEvent, ctx: Context[FrameEventOrError]): SyncDirective = ctx.pull()
    }
  }
}
