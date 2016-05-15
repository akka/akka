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
import akka.stream.stage.{ Context, GraphStage, SyncDirective, TerminationDirective }
import akka.stream._
import akka.stream.scaladsl.{ Sink, Source, Flow, Keep }
import akka.stream.impl.fusing.GraphStages.SimpleLinearGraphStage
import akka.stream.scaladsl._
import akka.stream.stage._

/**
 * The frame handler validates frames, multiplexes data to the user handler or to the bypass and
 * UTF-8 decodes text frames.
 *
 * INTERNAL API
 */
private[http] object FrameHandler {

  def create(server: Boolean): Flow[FrameEventOrError, NotUsed, NotUsed] =
    Flow[FrameEventOrError].via(Flow.fromGraph(new HandlerStage(server)))

  private class HandlerStage(server: Boolean) extends GraphStage[FlowShape[FrameEventOrError, ByteString]] {
    val out: Outlet[ByteString] = Outlet("HandlerStage.out")
    val in: Inlet[FrameEventOrError] = Inlet("HandlerStage.in")
    val shape: FlowShape[FrameEventOrError, ByteString] = FlowShape.of(in, out)

    override def createLogic(inheritedAttributes: Attributes): GraphStageLogic = new GraphStageLogic(shape) {

      override def toString: String = s"HandlerStage(server=$server)"
      private object Idle extends StateWithControlFrameHandling {
        def handleRegularFrameStart(start: FrameStart): Unit =
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

        def handleRegularFrameStart(start: FrameStart): Unit = {
          if ((expectFirstHeader && start.header.opcode == expectedOpcode) // first opcode must be the expected
            || start.header.opcode == Opcode.Continuation) { // further ones continuations
            expectFirstHeader = false

            if (start.header.fin) finSeen = true
            publish(start)
          } else protocolError()
        }
        //override def handleFrameData(data: FrameData)(implicit ctx: Ctx): SyncDirective = publish(data)
        override def handleFrameData(data: FrameData): Unit = publish(data)

        private def publish(part: FrameEvent): Unit =
          try publishMessagePart(createMessagePart(part.data, last = finSeen && part.lastPart))
          catch {
            case NonFatal(e) ⇒ closeWithCode(Protocol.CloseCodes.InconsistentData)
          }
      }

      private class CollectingControlFrame(opcode: Opcode, _data: ByteString, nextState: State) extends InFrameState {
        var data = _data

        def handleFrameData(data: FrameData): Unit = {
          this.data ++= data.data
          if (data.lastPart) handleControlFrame(opcode, this.data, nextState)
          else pull(in)
        }
      }

      private def becomeAndHandleWith(newState: State, part: FrameEvent): Unit = {
        setHandler(in, newState)
        current.onPush(part, out)
      }

      /** Returns a SyncDirective if it handled the message */
      private def validateHeader(header: FrameHeader): Option[Unit] = header match {
        case h: FrameHeader if h.mask.isDefined && !server ⇒ Some(protocolError())
        case h: FrameHeader if h.rsv1 || h.rsv2 || h.rsv3 ⇒ Some(protocolError())
        case FrameHeader(op, _, length, fin, _, _, _) if op.isControl && (length > 125 || !fin) ⇒ Some(protocolError())
        case _ ⇒ None
      }

      private def handleControlFrame(opcode: Opcode, data: ByteString, nextState: State): Unit = {
        setHandler(in, nextState)
        opcode match {
          case Opcode.Ping ⇒ publishDirectResponse(FrameEvent.fullFrame(Opcode.Pong, None, data, fin = true))
          case Opcode.Pong ⇒
            // ignore unsolicited Pong frame
            pull(in)
          case Opcode.Close ⇒
            setHandler(in, WaitForPeerTcpClose)
            push(out, PeerClosed.parse(data))
          case Opcode.Other(o) ⇒ closeWithCode(Protocol.CloseCodes.ProtocolError, "Unsupported opcode")
          case other           ⇒ fail(out, new IllegalStateException(s"unexpected message of type [${other.getClass.getName}] when expecting ControlFrame"))
        }
      }

      private def collectControlFrame(start: FrameStart, nextState: State): Unit = {
        require(!start.isFullMessage)
        setHandler(in, new CollectingControlFrame(start.header.opcode, start.data, nextState))
        pull(in)
      }

      private def publishMessagePart(part: MessageDataPart): Unit =
        if (part.last) emitMultiple(out, Iterator(part, MessageEnd))
        else push(out, part)

      private def publishDirectResponse(frame: FrameStart): Unit =
        push(out, DirectAnswer(frame))

      private def protocolError(reason: String = ""): Unit =
        closeWithCode(Protocol.CloseCodes.ProtocolError, reason)

      private def closeWithCode(closeCode: Int, reason: String = "", cause: Throwable = null): Unit = {
        setHandler(in, CloseAfterPeerClosed)
        push(out, ActivelyCloseWithCode(Some(closeCode), reason))
      }

      private object CloseAfterPeerClosed extends InHandler with OutHandler {
        def onPush: Unit = {
          val elem = grab(in)
          elem match {
            case FrameStart(FrameHeader(Opcode.Close, _, length, _, _, _, _), data) ⇒
              setHandler(in, WaitForPeerTcpClose)
              push(out, PeerClosed.parse(data))
            case _ ⇒ pull(in) // ignore all other data
          }
        }
      }

      private object WaitForPeerTcpClose extends InHandler with OutHandler {
        def onPush: Unit = {
          val elem = grab(in)
          pull(in) // ignore
        }
      }

      private abstract class StateWithControlFrameHandling extends BetweenFrameState {
        def handleRegularFrameStart(start: FrameStart): Unit

        def handleFrameStart(start: FrameStart): Unit =
          validateHeader(start.header).getOrElse {
            if (start.header.opcode.isControl)
              if (start.isFullMessage) handleControlFrame(start.header.opcode, start.data, this)
              else collectControlFrame(start, this)
            else handleRegularFrameStart(start)
          }
      }
      private abstract class BetweenFrameState extends ImplicitContextState {
        def handleFrameData(data: FrameData): Unit =
          throw new IllegalStateException("Expected FrameStart")
      }
      private abstract class InFrameState extends ImplicitContextState {
        def handleFrameStart(start: FrameStart): Unit =
          throw new IllegalStateException("Expected FrameData")
      }

      private abstract class ImplicitContextState extends InHandler with OutHandler {
        def handleFrameData(data: FrameData): Unit
        def handleFrameStart(start: FrameStart): Unit

        override def onPush(): Unit = {
          val part = grab(in)
          part match {
            case data: FrameData   ⇒ handleFrameData(data)
            case start: FrameStart ⇒ handleFrameStart(start)
            case FrameError(ex)    ⇒ fail(out, ex)
          }
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
}
