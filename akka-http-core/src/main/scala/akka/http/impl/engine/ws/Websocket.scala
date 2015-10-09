/*
 * Copyright (C) 2009-2015 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.impl.engine.ws

import java.util.Random

import akka.util.ByteString

import scala.concurrent.duration._

import akka.stream._
import akka.stream.scaladsl._
import akka.stream.stage._
import FlexiRoute.{ DemandFrom, DemandFromAny, RouteLogic }
import FlexiMerge.MergeLogic

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
            closeTimeout: FiniteDuration = 3.seconds): BidiFlow[FrameEvent, Message, Message, FrameEvent, Unit] =
    masking(serverSide, maskingRandomFactory) atop
      frameHandling(serverSide, closeTimeout) atop
      messageAPI(serverSide, closeTimeout)

  /** The lowest layer that implements the binary protocol */
  def framing: BidiFlow[ByteString, FrameEvent, FrameEvent, ByteString, Unit] =
    BidiFlow.wrap(
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
                    closeTimeout: FiniteDuration): BidiFlow[FrameEvent, FrameHandler.Output, FrameOutHandler.Input, FrameStart, Unit] =
    BidiFlow.wrap(
      FrameHandler.create(server = serverSide),
      FrameOutHandler.create(serverSide, closeTimeout))(Keep.none)
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
          if (code.exists(Protocol.CloseCodes.isError)) ctx.fail(new ProtocolException(s"Peer closed connection with code $code"))
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

    /** Lifts onComplete and onError into events to be processed in the FlexiMerge */
    class LiftCompletions extends StatefulStage[FrameStart, AnyRef] {
      def initial: StageState[FrameStart, AnyRef] = SteadyState

      object SteadyState extends State {
        def onPush(elem: FrameStart, ctx: Context[AnyRef]): SyncDirective = ctx.push(elem)
      }
      class CompleteWith(last: AnyRef) extends State {
        def onPush(elem: FrameStart, ctx: Context[AnyRef]): SyncDirective =
          ctx.fail(new IllegalStateException("No push expected"))

        override def onPull(ctx: Context[AnyRef]): SyncDirective = ctx.pushAndFinish(last)
      }

      override def onUpstreamFinish(ctx: Context[AnyRef]): TerminationDirective = {
        become(new CompleteWith(UserHandlerCompleted))
        ctx.absorbTermination()
      }
      override def onUpstreamFailure(cause: Throwable, ctx: Context[AnyRef]): TerminationDirective = {
        become(new CompleteWith(UserHandlerErredOut(cause)))
        ctx.absorbTermination()
      }
    }

    /**
     * Distributes output from the FrameHandler into bypass and userFlow.
     */
    object BypassRouter
      extends FlexiRoute[Either[BypassEvent, MessagePart], FanOutShape2[Either[BypassEvent, MessagePart], BypassEvent, MessagePart]](new FanOutShape2("bypassRouter"), Attributes.name("bypassRouter")) {
      def createRouteLogic(s: FanOutShape2[Either[BypassEvent, MessagePart], BypassEvent, MessagePart]): RouteLogic[Either[BypassEvent, MessagePart]] =
        new RouteLogic[Either[BypassEvent, MessagePart]] {
          def initialState: State[_] = State(DemandFromAny(s)) { (ctx, out, ev) ⇒
            ev match {
              case Left(_) ⇒
                State(DemandFrom(s.out0)) { (ctx, _, ev) ⇒ // FIXME: #17004
                  ctx.emit(s.out0)(ev.left.get)
                  initialState
                }
              case Right(_) ⇒
                State(DemandFrom(s.out1)) { (ctx, _, ev) ⇒
                  ctx.emit(s.out1)(ev.right.get)
                  initialState
                }
            }
          }

          override def initialCompletionHandling: CompletionHandling = super.initialCompletionHandling.copy(
            onDownstreamFinish = { (ctx, out) ⇒
              if (out == s.out0) ctx.finish()
              SameState
            })
        }
    }
    /**
     * Merges bypass, user flow and tick source for consumption in the FrameOutHandler.
     */
    object BypassMerge extends FlexiMerge[AnyRef, FanInShape3[BypassEvent, AnyRef, Tick.type, AnyRef]](new FanInShape3("bypassMerge"), Attributes.name("bypassMerge")) {
      def createMergeLogic(s: FanInShape3[BypassEvent, AnyRef, Tick.type, AnyRef]): MergeLogic[AnyRef] =
        new MergeLogic[AnyRef] {
          def initialState: State[_] = Idle

          lazy val Idle = State[AnyRef](FlexiMerge.ReadAny(s.in0.asInstanceOf[Inlet[AnyRef]], s.in1.asInstanceOf[Inlet[AnyRef]], s.in2.asInstanceOf[Inlet[AnyRef]])) { (ctx, in, elem) ⇒
            ctx.emit(elem)
            SameState
          }

          override def initialCompletionHandling: CompletionHandling =
            CompletionHandling(
              onUpstreamFinish = { (ctx, in) ⇒
                if (in == s.in0) ctx.finish()
                SameState
              },
              onUpstreamFailure = { (ctx, in, cause) ⇒
                if (in == s.in0) ctx.fail(cause)
                SameState
              })
        }
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

    BidiFlow() { implicit b ⇒
      import FlowGraph.Implicits._

      val routePreparation = b.add(Flow[FrameHandler.Output].mapConcat(x ⇒ x :: x :: Nil))
      val split = b.add(BypassRouter)
      val tick = Source(closeTimeout, closeTimeout, Tick)
      val merge = b.add(BypassMerge)
      val messagePreparation = b.add(prepareMessages)
      val messageRendering = b.add(renderMessages.transform(() ⇒ new LiftCompletions))

      routePreparation.outlet ~> split.in

      // user handler
      split.out1 ~> messagePreparation
      messageRendering.outlet ~> merge.in1

      // bypass
      split.out0 ~> merge.in0

      // timeout support
      tick ~> merge.in2

      BidiShape(
        routePreparation.inlet,
        messagePreparation.outlet,
        messageRendering.inlet,
        merge.out)
    }.named("ws-message-api")
  }

  object Tick
  case object SwitchToWebsocketToken
}
