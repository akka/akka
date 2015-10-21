/*
 * Copyright (C) 2009-2015 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.impl.engine.ws

import java.util.Random

import akka.event.LoggingAdapter
import akka.util.ByteString

import scala.concurrent.duration._

import akka.stream._
import akka.stream.scaladsl._
import akka.stream.stage._

import akka.http.impl.util._
import akka.http.scaladsl.model.ws._

/**
 * INTERNAL API
 *
 * Defines components of the websocket stack.
 */
private[http] object Websocket {
  import FrameHandler._

  /**
   * A stack of all the higher WS layers between raw frames and the user API.
   */
  def stack(serverSide: Boolean,
            maskingRandomFactory: () ⇒ Random,
            closeTimeout: FiniteDuration = 3.seconds,
            log: LoggingAdapter): BidiFlow[FrameEvent, Message, Message, FrameEvent, Unit] =
    masking(serverSide, maskingRandomFactory) atop
      frameHandling(serverSide, closeTimeout, log) atop
      messageAPI(serverSide, closeTimeout)

  /** The lowest layer that implements the binary protocol */
  def framing: BidiFlow[ByteString, FrameEvent, FrameEvent, ByteString, Unit] =
    BidiFlow.fromFlowsMat(
      Flow[ByteString].transform(() ⇒ new FrameEventParser),
      Flow[FrameEvent].transform(() ⇒ new FrameEventRenderer))(Keep.none)
      .named("ws-framing")

  /** The layer that handles masking using the rules defined in the specification */
  def masking(serverSide: Boolean, maskingRandomFactory: () ⇒ Random): BidiFlow[FrameEvent, FrameEvent, FrameEvent, FrameEvent, Unit] =
    Masking(serverSide, maskingRandomFactory)
      .named("ws-masking")

  /**
   * The layer that implements all low-level frame handling, like handling control frames, collecting messages
   * from frames, decoding text messages, close handling, etc.
   */
  def frameHandling(serverSide: Boolean = true,
                    closeTimeout: FiniteDuration,
                    log: LoggingAdapter): BidiFlow[FrameEvent, FrameHandler.Output, FrameOutHandler.Input, FrameStart, Unit] =
    BidiFlow.fromFlowsMat(
      FrameHandler.create(server = serverSide),
      FrameOutHandler.create(serverSide, closeTimeout, log))(Keep.none)
      .named("ws-frame-handling")

  /**
   * The layer that provides the high-level user facing API on top of frame handling.
   */
  def messageAPI(serverSide: Boolean,
                 closeTimeout: FiniteDuration): BidiFlow[FrameHandler.Output, Message, Message, FrameOutHandler.Input, Unit] = {
    /** Completes this branch of the flow if no more messages are expected and converts close codes into errors */
    class PrepareForUserHandler extends PushStage[MessagePart, MessagePart] {
      var inMessage = false
      def onPush(elem: MessagePart, ctx: Context[MessagePart]): SyncDirective = elem match {
        case PeerClosed(code, reason) ⇒
          if (code.exists(Protocol.CloseCodes.isError)) ctx.fail(new PeerClosedConnectionException(code.get, reason))
          else if (inMessage) ctx.fail(new ProtocolException(s"Truncated message, peer closed connection in the middle of message."))
          else ctx.finish()
        case ActivelyCloseWithCode(code, reason) ⇒
          if (code.exists(Protocol.CloseCodes.isError)) ctx.fail(new ProtocolException(s"Closing connection with error code $code"))
          else ctx.fail(new IllegalStateException("Regular close from FrameHandler is unexpected"))
        case x: MessageDataPart ⇒
          inMessage = !x.last
          ctx.push(x)
        case x ⇒ ctx.push(x)
      }
    }

    /** Collects user-level API messages from MessageDataParts */
    val collectMessage: Flow[Source[MessageDataPart, Unit], Message, Unit] =
      Flow[Source[MessageDataPart, Unit]]
        .via(headAndTailFlow)
        .map {
          case (TextMessagePart(text, true), remaining) ⇒
            TextMessage.Strict(text)
          case (first @ TextMessagePart(text, false), remaining) ⇒
            TextMessage(
              (Source.single(first) ++ remaining)
                .collect {
                  case t: TextMessagePart if t.data.nonEmpty ⇒ t.data
                })
          case (BinaryMessagePart(data, true), remaining) ⇒
            BinaryMessage.Strict(data)
          case (first @ BinaryMessagePart(data, false), remaining) ⇒
            BinaryMessage(
              (Source.single(first) ++ remaining)
                .collect {
                  case t: BinaryMessagePart if t.data.nonEmpty ⇒ t.data
                })
        }

    def prepareMessages: Flow[MessagePart, Message, Unit] =
      Flow[MessagePart]
        .transform(() ⇒ new PrepareForUserHandler)
        .splitWhen(_.isMessageEnd) // FIXME using splitAfter from #16885 would simplify protocol a lot
        .map(_.collect {
          case m: MessageDataPart ⇒ m
        })
        .via(collectMessage)
        .named("ws-prepare-messages")

    def renderMessages: Flow[Message, FrameStart, Unit] =
      MessageToFrameRenderer.create(serverSide)
        .named("ws-render-messages")

    BidiFlow.fromGraph(FlowGraph.create() { implicit b ⇒
      import FlowGraph.Implicits._

      val split = b.add(BypassRouter)
      val tick = Source(closeTimeout, closeTimeout, Tick)
      val merge = b.add(BypassMerge)
      val messagePreparation = b.add(prepareMessages)
      val messageRendering = b.add(renderMessages.via(LiftCompletions))
      // val messageRendering = b.add(renderMessages.transform(() ⇒ new LiftCompletions))

      // user handler
      split.out1 ~> messagePreparation
      messageRendering.outlet ~> merge.in1

      // bypass
      split.out0 ~> merge.in0

      // timeout support
      tick ~> merge.in2

      BidiShape(
        split.in,
        messagePreparation.outlet,
        messageRendering.inlet,
        merge.out)
    }.named("ws-message-api"))
  }

  private object BypassRouter extends GraphStage[FanOutShape2[Output, BypassEvent, MessagePart]] {
    private val in = Inlet[Output]("in")
    private val bypass = Outlet[BypassEvent]("bypass-out")
    private val user = Outlet[MessagePart]("message-out")

    val shape = new FanOutShape2(in, bypass, user)

    def createLogic = new GraphStageLogic(shape) {

      setHandler(in, new InHandler {
        override def onPush(): Unit = {
          grab(in) match {
            case b: BypassEvent with MessagePart ⇒ emit(bypass, b, () ⇒ emit(user, b, pullIn))
            case b: BypassEvent                  ⇒ emit(bypass, b, pullIn)
            case m: MessagePart                  ⇒ emit(user, m, pullIn)
          }
        }
      })
      val pullIn = () ⇒ pull(in)

      setHandler(bypass, eagerTerminateOutput)
      setHandler(user, ignoreTerminateOutput)

      override def preStart(): Unit = {
        super.preStart()
        pullIn()
      }
    }
  }

  private object BypassMerge extends GraphStage[FanInShape3[BypassEvent, AnyRef, Tick.type, AnyRef]] {
    private val bypass = Inlet[BypassEvent]("bypass-in")
    private val user = Inlet[AnyRef]("message-in")
    private val tick = Inlet[Tick.type]("tick-in")
    private val out = Outlet[AnyRef]("out")

    val shape = new FanInShape3(bypass, user, tick, out)

    def createLogic = new GraphStageLogic(shape) {

      passAlong(bypass, out, doFinish = true, doFail = true)
      passAlong(user, out, doFinish = false, doFail = false)
      passAlong(tick, out, doFinish = false, doFail = false)

      setHandler(out, eagerTerminateOutput)

      override def preStart(): Unit = {
        super.preStart()
        pull(bypass)
        pull(user)
        pull(tick)
      }
    }
  }

  private object LiftCompletions extends GraphStage[FlowShape[FrameStart, AnyRef]] {
    private val in = Inlet[FrameStart]("in")
    private val out = Outlet[AnyRef]("out")

    val shape = new FlowShape(in, out)

    def createLogic = new GraphStageLogic(shape) {
      setHandler(out, new OutHandler {
        override def onPull(): Unit = pull(in)
      })
      setHandler(in, new InHandler {
        override def onPush(): Unit = push(out, grab(in))
        override def onUpstreamFinish(): Unit = emit(out, UserHandlerCompleted, () ⇒ completeStage())
        override def onUpstreamFailure(ex: Throwable): Unit = emit(out, UserHandlerErredOut(ex), () ⇒ completeStage())
      })
    }
  }

  object Tick
  case object SwitchToWebsocketToken
}
