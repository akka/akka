/*
 * Copyright (C) 2009-2015 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.impl.engine.ws

import akka.stream.scaladsl.Flow
import akka.stream.stage.{ TerminationDirective, SyncDirective, Context, StatefulStage }
import akka.util.ByteString
import Protocol.Opcode

import scala.util.control.NonFatal

/**
 * The frame handler validates frames, multiplexes data to the user handler or to the bypass and
 * UTF-8 decodes text frames.
 *
 * INTERNAL API
 */
private[http] object FrameHandler {
  def create(server: Boolean): Flow[FrameEvent, Either[BypassEvent, MessagePart], Unit] =
    Flow[FrameEvent].transform(() ⇒ new HandlerStage(server))

  class HandlerStage(server: Boolean) extends StatefulStage[FrameEvent, Either[BypassEvent, MessagePart]] {
    type Ctx = Context[Either[BypassEvent, MessagePart]]
    def initial: State = Idle

    object Idle extends StateWithControlFrameHandling {
      def handleRegularFrameStart(start: FrameStart)(implicit ctx: Ctx): SyncDirective =
        (start.header.opcode, start.isFullMessage) match {
          case (Opcode.Binary, true)  ⇒ publishMessagePart(BinaryMessagePart(start.data, last = true))
          case (Opcode.Binary, false) ⇒ becomeAndHandleWith(new CollectingBinaryMessage, start)
          case (Opcode.Text, _)       ⇒ becomeAndHandleWith(new CollectingTextMessage, start)
          case x                      ⇒ protocolError()
        }
    }

    class CollectingBinaryMessage extends CollectingMessageFrame(Opcode.Binary) {
      def createMessagePart(data: ByteString, last: Boolean): MessageDataPart = BinaryMessagePart(data, last)
    }
    class CollectingTextMessage extends CollectingMessageFrame(Opcode.Text) {
      val decoder = Utf8Decoder.create()

      def createMessagePart(data: ByteString, last: Boolean): MessageDataPart =
        TextMessagePart(decoder.decode(data, endOfInput = last).get, last)
    }

    abstract class CollectingMessageFrame(expectedOpcode: Opcode) extends StateWithControlFrameHandling {
      var expectFirstHeader = true
      var finSeen = false
      def createMessagePart(data: ByteString, last: Boolean): MessageDataPart

      def handleRegularFrameStart(start: FrameStart)(implicit ctx: Ctx): SyncDirective = {
        if ((expectFirstHeader && start.header.opcode == expectedOpcode) // first opcode must be the expected
          || start.header.opcode == Opcode.Continuation) { // further ones continuations
          expectFirstHeader = false

          if (start.header.fin) finSeen = true
          publish(start)
        } else protocolError()
      }
      override def handleFrameData(data: FrameData)(implicit ctx: Ctx): SyncDirective = publish(data)

      private def publish(part: FrameEvent)(implicit ctx: Ctx): SyncDirective =
        try publishMessagePart(createMessagePart(part.data, last = finSeen && part.lastPart))
        catch {
          case NonFatal(e) ⇒ closeWithCode(Protocol.CloseCodes.InconsistentData)
        }
    }
    class CollectingControlFrame(opcode: Opcode, _data: ByteString, nextState: State) extends InFrameState {
      var data = _data

      def handleFrameData(data: FrameData)(implicit ctx: Ctx): SyncDirective = {
        this.data ++= data.data
        if (data.lastPart) handleControlFrame(opcode, this.data, nextState)
        else ctx.pull()
      }
    }
    object Closed extends State {
      def onPush(elem: FrameEvent, ctx: Ctx): SyncDirective =
        ctx.pull() // ignore
    }

    def becomeAndHandleWith(newState: State, part: FrameEvent)(implicit ctx: Ctx): SyncDirective = {
      become(newState)
      current.onPush(part, ctx)
    }

    /** Returns a SyncDirective if it handled the message */
    def validateHeader(header: FrameHeader)(implicit ctx: Ctx): Option[SyncDirective] = header match {
      case h: FrameHeader if h.mask.isDefined && !server ⇒ Some(protocolError())
      case h: FrameHeader if h.rsv1 || h.rsv2 || h.rsv3 ⇒ Some(protocolError())
      case FrameHeader(op, _, length, fin, _, _, _) if op.isControl && (length > 125 || !fin) ⇒ Some(protocolError())
      case _ ⇒ None
    }

    def handleControlFrame(opcode: Opcode, data: ByteString, nextState: State)(implicit ctx: Ctx): SyncDirective = {
      become(nextState)
      opcode match {
        case Opcode.Ping ⇒ publishDirectResponse(FrameEvent.fullFrame(Opcode.Pong, None, data, fin = true))
        case Opcode.Pong ⇒
          // ignore unsolicited Pong frame
          ctx.pull()
        case Opcode.Close ⇒
          val closeCode = FrameEventParser.parseCloseCode(data)
          emit(Iterator(Left(PeerClosed(closeCode)), Right(PeerClosed(closeCode))), ctx, WaitForPeerTcpClose)
        case Opcode.Other(o) ⇒ closeWithCode(Protocol.CloseCodes.ProtocolError, "Unsupported opcode")
      }
    }
    private def collectControlFrame(start: FrameStart, nextState: State)(implicit ctx: Ctx): SyncDirective = {
      assert(!start.isFullMessage)
      become(new CollectingControlFrame(start.header.opcode, start.data, nextState))
      ctx.pull()
    }

    private def publishMessagePart(part: MessageDataPart)(implicit ctx: Ctx): SyncDirective =
      if (part.last) emit(Iterator(Right(part), Right(MessageEnd)), ctx, Idle)
      else ctx.push(Right(part))
    private def publishDirectResponse(frame: FrameStart)(implicit ctx: Ctx): SyncDirective =
      ctx.push(Left(DirectAnswer(frame)))

    private def protocolError(reason: String = "")(implicit ctx: Ctx): SyncDirective =
      closeWithCode(Protocol.CloseCodes.ProtocolError, reason)

    private def closeWithCode(closeCode: Int, reason: String = "", cause: Throwable = null)(implicit ctx: Ctx): SyncDirective =
      emit(
        Iterator(
          Left(ActivelyCloseWithCode(Some(closeCode), reason)),
          Right(ActivelyCloseWithCode(Some(closeCode), reason))), ctx, CloseAfterPeerClosed)

    object CloseAfterPeerClosed extends State {
      def onPush(elem: FrameEvent, ctx: Context[Either[BypassEvent, MessagePart]]): SyncDirective =
        elem match {
          case FrameStart(FrameHeader(Opcode.Close, _, length, _, _, _, _), data) ⇒
            become(WaitForPeerTcpClose)
            ctx.push(Left(PeerClosed(FrameEventParser.parseCloseCode(data))))

          case _ ⇒ ctx.pull() // ignore all other data
        }
    }
    object WaitForPeerTcpClose extends State {
      def onPush(elem: FrameEvent, ctx: Context[Either[BypassEvent, MessagePart]]): SyncDirective =
        ctx.pull() // ignore
    }

    abstract class StateWithControlFrameHandling extends BetweenFrameState {
      def handleRegularFrameStart(start: FrameStart)(implicit ctx: Ctx): SyncDirective

      def handleFrameStart(start: FrameStart)(implicit ctx: Ctx): SyncDirective =
        validateHeader(start.header).getOrElse {
          if (start.header.opcode.isControl)
            if (start.isFullMessage) handleControlFrame(start.header.opcode, start.data, this)
            else collectControlFrame(start, this)
          else handleRegularFrameStart(start)
        }
    }
    abstract class BetweenFrameState extends ImplicitContextState {
      def handleFrameData(data: FrameData)(implicit ctx: Ctx): SyncDirective =
        throw new IllegalStateException("Expected FrameStart")
    }
    abstract class InFrameState extends ImplicitContextState {
      def handleFrameStart(start: FrameStart)(implicit ctx: Ctx): SyncDirective =
        throw new IllegalStateException("Expected FrameData")
    }
    abstract class ImplicitContextState extends State {
      def handleFrameData(data: FrameData)(implicit ctx: Ctx): SyncDirective
      def handleFrameStart(start: FrameStart)(implicit ctx: Ctx): SyncDirective

      def onPush(part: FrameEvent, ctx: Ctx): SyncDirective =
        part match {
          case data: FrameData   ⇒ handleFrameData(data)(ctx)
          case start: FrameStart ⇒ handleFrameStart(start)(ctx)
        }
    }
  }

  sealed trait MessagePart {
    def isMessageEnd: Boolean
  }
  sealed trait MessageDataPart extends MessagePart {
    def isMessageEnd = false
    def last: Boolean
  }
  final case class TextMessagePart(data: String, last: Boolean) extends MessageDataPart
  final case class BinaryMessagePart(data: ByteString, last: Boolean) extends MessageDataPart
  case object MessageEnd extends MessagePart {
    def isMessageEnd: Boolean = true
  }
  final case class PeerClosed(code: Option[Int], reason: String = "") extends MessagePart with BypassEvent {
    def isMessageEnd: Boolean = true
  }

  sealed trait BypassEvent
  final case class DirectAnswer(frame: FrameStart) extends BypassEvent
  final case class ActivelyCloseWithCode(code: Option[Int], reason: String = "") extends MessagePart with BypassEvent {
    def isMessageEnd: Boolean = true
  }
  case object UserHandlerCompleted extends BypassEvent
  case class UserHandlerErredOut(cause: Throwable) extends BypassEvent
}
