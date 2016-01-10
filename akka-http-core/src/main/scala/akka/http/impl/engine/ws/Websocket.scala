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
import akka.http.scaladsl.model.ws._
import akka.stream.impl.fusing.SubSource

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
    BidiFlow.fromFlows(
      Flow[ByteString].via(FrameEventParser),
      Flow[FrameEvent].transform(() ⇒ new FrameEventRenderer))
      .named("ws-framing")

  /** The layer that handles masking using the rules defined in the specification */
  def masking(serverSide: Boolean, maskingRandomFactory: () ⇒ Random): BidiFlow[FrameEvent, FrameEventOrError, FrameEvent, FrameEvent, Unit] =
    Masking(serverSide, maskingRandomFactory)
      .named("ws-masking")

  /**
   * The layer that implements all low-level frame handling, like handling control frames, collecting messages
   * from frames, decoding text messages, close handling, etc.
   */
  def frameHandling(serverSide: Boolean = true,
                    closeTimeout: FiniteDuration,
                    log: LoggingAdapter): BidiFlow[FrameEventOrError, FrameHandler.Output, FrameOutHandler.Input, FrameStart, Unit] =
    BidiFlow.fromFlows(
      FrameHandler.create(server = serverSide),
      FrameOutHandler.create(serverSide, closeTimeout, log))
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
    val collectMessage: Flow[MessageDataPart, Message, Unit] =
      Flow[MessageDataPart]
        .prefixAndTail(1)
        .mapConcat {
          // happens if we get a MessageEnd first which creates a new substream but which is then
          // filtered out by collect in `prepareMessages` below
          case (Nil, _) ⇒ Nil
          case (first +: Nil, remaining) ⇒ (first match {
            case TextMessagePart(text, true) ⇒
              SubSource.kill(remaining)
              TextMessage.Strict(text)
            case first @ TextMessagePart(text, false) ⇒
              TextMessage(
                (Source.single(first) ++ remaining)
                  .collect {
                    case t: TextMessagePart if t.data.nonEmpty ⇒ t.data
                  })
            case BinaryMessagePart(data, true) ⇒
              SubSource.kill(remaining)
              BinaryMessage.Strict(data)
            case first @ BinaryMessagePart(data, false) ⇒
              BinaryMessage(
                (Source.single(first) ++ remaining)
                  .collect {
                    case t: BinaryMessagePart if t.data.nonEmpty ⇒ t.data
                  })
          }) :: Nil
        }

    def prepareMessages: Flow[MessagePart, Message, Unit] =
      Flow[MessagePart]
        .transform(() ⇒ new PrepareForUserHandler)
        .splitWhen(_.isMessageEnd) // FIXME using splitAfter from #16885 would simplify protocol a lot
        .collect {
          case m: MessageDataPart ⇒ m
        }
        .via(collectMessage)
        .concatSubstreams
        .named("ws-prepare-messages")

    def renderMessages: Flow[Message, FrameStart, Unit] =
      MessageToFrameRenderer.create(serverSide)
        .named("ws-render-messages")

    BidiFlow.fromGraph(GraphDSL.create() { implicit b ⇒
      import GraphDSL.Implicits._

      val split = b.add(BypassRouter)
      val tick = Source.tick(closeTimeout, closeTimeout, Tick)
      val merge = b.add(BypassMerge)
      val messagePreparation = b.add(prepareMessages)
      val messageRendering = b.add(renderMessages.via(LiftCompletions))

      // user handler
      split.out1 ~> messagePreparation
      messageRendering.outlet ~> merge.in1

      // bypass
      split.out0 ~> merge.in0

      // timeout support
      tick ~> merge.in2

      BidiShape(
        split.in,
        messagePreparation.out,
        messageRendering.in,
        merge.out)
    }.named("ws-message-api"))
  }

  private case object BypassRouter extends GraphStage[FanOutShape2[Output, BypassEvent, MessagePart]] {
    private val in = Inlet[Output]("in")
    private val bypass = Outlet[BypassEvent]("bypass-out")
    private val user = Outlet[MessagePart]("message-out")

    override def initialAttributes = Attributes.name("BypassRouter")

    val shape = new FanOutShape2(in, bypass, user)

    def createLogic(effectiveAttributes: Attributes) = new GraphStageLogic(shape) {

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

  private case object BypassMerge extends GraphStage[FanInShape3[BypassEvent, AnyRef, Tick.type, AnyRef]] {
    private val bypass = Inlet[BypassEvent]("bypass-in")
    private val user = Inlet[AnyRef]("message-in")
    private val tick = Inlet[Tick.type]("tick-in")
    private val out = Outlet[AnyRef]("out")

    override def initialAttributes = Attributes.name("BypassMerge")

    val shape = new FanInShape3(bypass, user, tick, out)

    def createLogic(effectiveAttributes: Attributes) = new GraphStageLogic(shape) {

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

  private case object LiftCompletions extends GraphStage[FlowShape[FrameStart, AnyRef]] {
    private val in = Inlet[FrameStart]("in")
    private val out = Outlet[AnyRef]("out")

    override def initialAttributes = Attributes.name("LiftCompletions")

    val shape = new FlowShape(in, out)

    def createLogic(effectiveAttributes: Attributes) = new GraphStageLogic(shape) {
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

  case object Tick
}
