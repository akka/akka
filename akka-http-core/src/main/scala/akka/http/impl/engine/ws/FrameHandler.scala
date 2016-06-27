/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.impl.engine.ws

import akka.NotUsed
import akka.stream.scaladsl.Flow
import akka.util.ByteString
import Protocol.Opcode
import akka.event.Logging
import akka.stream.stage.{ GraphStage, GraphStageLogic, InHandler, OutHandler }
import akka.stream.{ Attributes, FlowShape, Inlet, Outlet }

import scala.util.control.NonFatal

/**
 * The frame handler validates frames, multiplexes data to the user handler or to the bypass and
 * UTF-8 decodes text frames.
 *
 * INTERNAL API
 */
private[http] object FrameHandler {

  def create(server: Boolean): Flow[FrameEventOrError, Output, NotUsed] =
    Flow[FrameEventOrError].via(new HandlerStage(server))

  private class HandlerStage(server: Boolean) extends GraphStage[FlowShape[FrameEventOrError, Output]] {
    val in = Inlet[FrameEventOrError](Logging.simpleName(this) + ".in")
    val out = Outlet[Output](Logging.simpleName(this) + ".out")
    override val shape = FlowShape(in, out)

    override def toString: String = s"HandlerStage(server=$server)"

    override def createLogic(attributes: Attributes): GraphStageLogic =
      new GraphStageLogic(shape) with OutHandler {
        setHandler(out, this)
        setHandler(in, IdleHandler)

        override def onPull(): Unit = pull(in)

        private object IdleHandler extends ControlFrameStartHandler {
          def setAndHandleFrameStartWith(newHandler: ControlFrameStartHandler, start: FrameStart): Unit = {
            setHandler(in, newHandler)
            newHandler.handleFrameStart(start)
          }

          override def handleRegularFrameStart(start: FrameStart): Unit =
            (start.header.opcode, start.isFullMessage) match {
              case (Opcode.Binary, true)  ⇒ publishMessagePart(BinaryMessagePart(start.data, last = true))
              case (Opcode.Binary, false) ⇒ setAndHandleFrameStartWith(new BinaryMessagehandler, start)
              case (Opcode.Text, _)       ⇒ setAndHandleFrameStartWith(new TextMessageHandler, start)
              case x                      ⇒ pushProtocolError()
            }
        }

        private class BinaryMessagehandler extends MessageHandler(Opcode.Binary) {
          override def createMessagePart(data: ByteString, last: Boolean): MessageDataPart =
            BinaryMessagePart(data, last)
        }

        private class TextMessageHandler extends MessageHandler(Opcode.Text) {
          val decoder = Utf8Decoder.create()

          override def createMessagePart(data: ByteString, last: Boolean): MessageDataPart =
            TextMessagePart(decoder.decode(data, endOfInput = last).get, last)
        }

        private abstract class MessageHandler(expectedOpcode: Opcode) extends ControlFrameStartHandler {
          var expectFirstHeader = true
          var finSeen = false
          def createMessagePart(data: ByteString, last: Boolean): MessageDataPart

          override def handleRegularFrameStart(start: FrameStart): Unit = {
            if ((expectFirstHeader && start.header.opcode == expectedOpcode) // first opcode must be the expected
              || start.header.opcode == Opcode.Continuation) { // further ones continuations
              expectFirstHeader = false

              if (start.header.fin) finSeen = true
              publish(start)
            } else pushProtocolError()
          }

          override def handleFrameData(data: FrameData): Unit = publish(data)

          def publish(part: FrameEvent): Unit = try {
            publishMessagePart(createMessagePart(part.data, last = finSeen && part.lastPart))
          } catch {
            case NonFatal(e) ⇒ closeWithCode(Protocol.CloseCodes.InconsistentData)
          }
        }

        private trait ControlFrameStartHandler extends FrameHandler {
          def handleRegularFrameStart(start: FrameStart): Unit

          override def handleFrameStart(start: FrameStart): Unit = start.header match {
            case h: FrameHeader if h.mask.isDefined && !server                                      ⇒ pushProtocolError()
            case h: FrameHeader if h.rsv1 || h.rsv2 || h.rsv3                                       ⇒ pushProtocolError()
            case FrameHeader(op, _, length, fin, _, _, _) if op.isControl && (length > 125 || !fin) ⇒ pushProtocolError()
            case h: FrameHeader if h.opcode.isControl ⇒
              if (start.isFullMessage) handleControlFrame(h.opcode, start.data, this)
              else collectControlFrame(start, this)
            case _ ⇒ handleRegularFrameStart(start)
          }

          override def handleFrameData(data: FrameData): Unit =
            throw new IllegalStateException("Expected FrameStart")
        }

        private class ControlFrameDataHandler(opcode: Opcode, _data: ByteString, nextHandler: InHandler) extends FrameHandler {
          var data = _data

          override def handleFrameData(data: FrameData): Unit = {
            this.data ++= data.data
            if (data.lastPart) handleControlFrame(opcode, this.data, nextHandler)
            else pull(in)
          }

          override def handleFrameStart(start: FrameStart): Unit =
            throw new IllegalStateException("Expected FrameData")
        }

        private trait FrameHandler extends InHandler {
          def handleFrameData(data: FrameData): Unit
          def handleFrameStart(start: FrameStart): Unit

          def handleControlFrame(opcode: Opcode, data: ByteString, nextHandler: InHandler): Unit = {
            setHandler(in, nextHandler)
            opcode match {
              case Opcode.Ping ⇒ publishDirectResponse(FrameEvent.fullFrame(Opcode.Pong, None, data, fin = true))
              case Opcode.Pong ⇒
                // ignore unsolicited Pong frame
                pull(in)
              case Opcode.Close ⇒
                setHandler(in, WaitForPeerTcpClose)
                push(out, PeerClosed.parse(data))
              case Opcode.Other(o) ⇒ closeWithCode(Protocol.CloseCodes.ProtocolError, "Unsupported opcode")
              case other ⇒ failStage(
                new IllegalStateException(s"unexpected message of type [${other.getClass.getName}] when expecting ControlFrame")
              )
            }
          }

          def pushProtocolError(): Unit = closeWithCode(Protocol.CloseCodes.ProtocolError)

          def closeWithCode(closeCode: Int, reason: String = ""): Unit = {
            setHandler(in, CloseAfterPeerClosed)
            push(out, ActivelyCloseWithCode(Some(closeCode), reason))
          }

          def collectControlFrame(start: FrameStart, nextHandler: InHandler): Unit = {
            require(!start.isFullMessage)
            setHandler(in, new ControlFrameDataHandler(start.header.opcode, start.data, nextHandler))
            pull(in)
          }

          def publishMessagePart(part: MessageDataPart): Unit =
            if (part.last) emitMultiple(out, Iterator(part, MessageEnd), () ⇒ setHandler(in, IdleHandler))
            else push(out, part)

          def publishDirectResponse(frame: FrameStart): Unit = push(out, DirectAnswer(frame))

          override def onPush(): Unit = grab(in) match {
            case data: FrameData   ⇒ handleFrameData(data)
            case start: FrameStart ⇒ handleFrameStart(start)
            case FrameError(ex)    ⇒ failStage(ex)
          }
        }

        private object CloseAfterPeerClosed extends InHandler {
          override def onPush(): Unit = grab(in) match {
            case FrameStart(FrameHeader(Opcode.Close, _, length, _, _, _, _), data) ⇒
              setHandler(in, WaitForPeerTcpClose)
              push(out, PeerClosed.parse(data))
            case _ ⇒ pull(in) // ignore all other data
          }
        }

        private object WaitForPeerTcpClose extends InHandler {
          override def onPush(): Unit = pull(in) // ignore
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
