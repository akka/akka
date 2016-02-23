/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.impl.engine.ws

import akka.NotUsed
import akka.stream.scaladsl.Flow
import akka.stream.stage.{ SyncDirective, Context, StatefulStage }
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

  def create(server: Boolean): Flow[FrameEventOrError, Output, NotUsed] =
    Flow[FrameEventOrError].transform(() ⇒ new HandlerStage(server))

  private class HandlerStage(server: Boolean) extends StatefulStage[FrameEventOrError, Output] {
    type Ctx = Context[Output]
    def initial: State = Idle

    override def toString: String = s"HandlerStage(server=$server)"

    private object Idle extends StateWithControlFrameHandling {
      def handleRegularFrameStart(start: FrameStart)(implicit ctx: Ctx): SyncDirective =
        (start.header.opcode, start.isFullMessage) match {
          case (Opcode.Binary, true)  ⇒ publishMessagePart(BinaryMessagePart(start.data, last = true))
          case (Opcode.Binary, false) ⇒ becomeAndHandleWith(new CollectingBinaryMessage, start)
          case (Opcode.Text, _)       ⇒ becomeAndHandleWith(new CollectingTextMessage, start)
          case x                      ⇒ protocolError()
        }
    }

    private class CollectingBinaryMessage extends CollectingMessageFrame(Opcode.Binary) {
      def createMessagePart(data: ByteString, last: Boolean): MessageDataPart = BinaryMessagePart(data, last)
    }
    private class CollectingTextMessage extends CollectingMessageFrame(Opcode.Text) {
      val decoder = Utf8Decoder.create()

      def createMessagePart(data: ByteString, last: Boolean): MessageDataPart =
        TextMessagePart(decoder.decode(data, endOfInput = last).get, last)
    }

    private abstract class CollectingMessageFrame(expectedOpcode: Opcode) extends StateWithControlFrameHandling {
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

    private class CollectingControlFrame(opcode: Opcode, _data: ByteString, nextState: State) extends InFrameState {
      var data = _data

      def handleFrameData(data: FrameData)(implicit ctx: Ctx): SyncDirective = {
        this.data ++= data.data
        if (data.lastPart) handleControlFrame(opcode, this.data, nextState)
        else ctx.pull()
      }
    }

    private def becomeAndHandleWith(newState: State, part: FrameEvent)(implicit ctx: Ctx): SyncDirective = {
      become(newState)
      current.onPush(part, ctx)
    }

    /** Returns a SyncDirective if it handled the message */
    private def validateHeader(header: FrameHeader)(implicit ctx: Ctx): Option[SyncDirective] = header match {
      case h: FrameHeader if h.mask.isDefined && !server ⇒ Some(protocolError())
      case h: FrameHeader if h.rsv1 || h.rsv2 || h.rsv3 ⇒ Some(protocolError())
      case FrameHeader(op, _, length, fin, _, _, _) if op.isControl && (length > 125 || !fin) ⇒ Some(protocolError())
      case _ ⇒ None
    }

    private def handleControlFrame(opcode: Opcode, data: ByteString, nextState: State)(implicit ctx: Ctx): SyncDirective = {
      become(nextState)
      opcode match {
        case Opcode.Ping ⇒ publishDirectResponse(FrameEvent.fullFrame(Opcode.Pong, None, data, fin = true))
        case Opcode.Pong ⇒
          // ignore unsolicited Pong frame
          ctx.pull()
        case Opcode.Close ⇒
          become(WaitForPeerTcpClose)
          ctx.push(PeerClosed.parse(data))
        case Opcode.Other(o) ⇒ closeWithCode(Protocol.CloseCodes.ProtocolError, "Unsupported opcode")
        case other           ⇒ ctx.fail(new IllegalStateException(s"unexpected message of type [${other.getClass.getName}] when expecting ControlFrame"))
      }
    }
    private def collectControlFrame(start: FrameStart, nextState: State)(implicit ctx: Ctx): SyncDirective = {
      require(!start.isFullMessage)
      become(new CollectingControlFrame(start.header.opcode, start.data, nextState))
      ctx.pull()
    }

    private def publishMessagePart(part: MessageDataPart)(implicit ctx: Ctx): SyncDirective =
      if (part.last) emit(Iterator(part, MessageEnd), ctx, Idle)
      else ctx.push(part)
    private def publishDirectResponse(frame: FrameStart)(implicit ctx: Ctx): SyncDirective =
      ctx.push(DirectAnswer(frame))

    private def protocolError(reason: String = "")(implicit ctx: Ctx): SyncDirective =
      closeWithCode(Protocol.CloseCodes.ProtocolError, reason)

    private def closeWithCode(closeCode: Int, reason: String = "", cause: Throwable = null)(implicit ctx: Ctx): SyncDirective = {
      become(CloseAfterPeerClosed)
      ctx.push(ActivelyCloseWithCode(Some(closeCode), reason))
    }

    private object CloseAfterPeerClosed extends State {
      def onPush(elem: FrameEventOrError, ctx: Context[Output]): SyncDirective =
        elem match {
          case FrameStart(FrameHeader(Opcode.Close, _, length, _, _, _, _), data) ⇒
            become(WaitForPeerTcpClose)
            ctx.push(PeerClosed.parse(data))
          case _ ⇒ ctx.pull() // ignore all other data
        }
    }
    private object WaitForPeerTcpClose extends State {
      def onPush(elem: FrameEventOrError, ctx: Context[Output]): SyncDirective =
        ctx.pull() // ignore
    }

    private abstract class StateWithControlFrameHandling extends BetweenFrameState {
      def handleRegularFrameStart(start: FrameStart)(implicit ctx: Ctx): SyncDirective

      def handleFrameStart(start: FrameStart)(implicit ctx: Ctx): SyncDirective =
        validateHeader(start.header).getOrElse {
          if (start.header.opcode.isControl)
            if (start.isFullMessage) handleControlFrame(start.header.opcode, start.data, this)
            else collectControlFrame(start, this)
          else handleRegularFrameStart(start)
        }
    }
    private abstract class BetweenFrameState extends ImplicitContextState {
      def handleFrameData(data: FrameData)(implicit ctx: Ctx): SyncDirective =
        throw new IllegalStateException("Expected FrameStart")
    }
    private abstract class InFrameState extends ImplicitContextState {
      def handleFrameStart(start: FrameStart)(implicit ctx: Ctx): SyncDirective =
        throw new IllegalStateException("Expected FrameData")
    }
    private abstract class ImplicitContextState extends State {
      def handleFrameData(data: FrameData)(implicit ctx: Ctx): SyncDirective
      def handleFrameStart(start: FrameStart)(implicit ctx: Ctx): SyncDirective

      def onPush(part: FrameEventOrError, ctx: Ctx): SyncDirective =
        part match {
          case data: FrameData   ⇒ handleFrameData(data)(ctx)
          case start: FrameStart ⇒ handleFrameStart(start)(ctx)
          case FrameError(ex)    ⇒ ctx.fail(ex)
        }
    }
  }

  sealed trait Output

  sealed trait MessagePart extends Output {
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
  object PeerClosed {
    def parse(data: ByteString): PeerClosed =
      FrameEventParser.parseCloseCode(data) match {
        case Some((code, reason)) ⇒ PeerClosed(Some(code), reason)
        case None                 ⇒ PeerClosed(None)
      }
  }

  sealed trait BypassEvent extends Output
  final case class DirectAnswer(frame: FrameStart) extends BypassEvent
  final case class ActivelyCloseWithCode(code: Option[Int], reason: String = "") extends MessagePart with BypassEvent {
    def isMessageEnd: Boolean = true
  }
  case object UserHandlerCompleted extends BypassEvent
  case class UserHandlerErredOut(cause: Throwable) extends BypassEvent
}
