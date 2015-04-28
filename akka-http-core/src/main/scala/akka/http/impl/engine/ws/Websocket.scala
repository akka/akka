/*
 * Copyright (C) 2009-2015 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.impl.engine.ws

import java.security.SecureRandom

import scala.concurrent.duration._

import akka.stream.{ OperationAttributes, FanOutShape2, FanInShape3, Inlet }
import akka.stream.scaladsl._
import akka.stream.stage._
import FlexiRoute.{ DemandFrom, DemandFromAny, RouteLogic }
import FlexiMerge.MergeLogic

import akka.http.impl.util._
import akka.http.scaladsl.model.ws._

/**
 * INTERNAL API
 */
private[http] object Websocket {
  import FrameHandler._

  def handleMessages[T](messageHandler: Flow[Message, Message, T],
                        serverSide: Boolean = true,
                        closeTimeout: FiniteDuration = 3.seconds): Flow[FrameEvent, FrameEvent, Unit] = {
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
            TextMessage.Streamed(
              (Source.single(first) ++ remaining)
                .collect {
                  case t: TextMessagePart if t.data.nonEmpty ⇒ t.data
                })
          case (BinaryMessagePart(data, true), remaining) ⇒
            BinaryMessage.Strict(data)
          case (first @ BinaryMessagePart(data, false), remaining) ⇒
            BinaryMessage.Streamed(
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

    lazy val userFlow =
      Flow[MessagePart]
        .transform(() ⇒ new PrepareForUserHandler)
        .splitWhen(_.isMessageEnd) // FIXME using splitAfter from #16885 would simplify protocol a lot
        .map(_.collect {
          case m: MessageDataPart ⇒ m
        })
        .via(collectMessage)
        .via(messageHandler)
        .via(MessageToFrameRenderer.create(serverSide))
        .transform(() ⇒ new LiftCompletions)

    /**
     * Distributes output from the FrameHandler into bypass and userFlow.
     */
    object BypassRouter
      extends FlexiRoute[Either[BypassEvent, MessagePart], FanOutShape2[Either[BypassEvent, MessagePart], BypassEvent, MessagePart]](new FanOutShape2("bypassRouter"), OperationAttributes.name("bypassRouter")) {
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
    object BypassMerge extends FlexiMerge[AnyRef, FanInShape3[BypassEvent, AnyRef, Tick.type, AnyRef]](new FanInShape3("bypassMerge"), OperationAttributes.name("bypassMerge")) {
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

    lazy val bypassAndUserHandler: Flow[Either[BypassEvent, MessagePart], AnyRef, Unit] =
      Flow(BypassRouter, Source(closeTimeout, closeTimeout, Tick), BypassMerge)((_, _, _) ⇒ ()) { implicit b ⇒
        (split, tick, merge) ⇒
          import FlowGraph.Implicits._

          split.out0 ~> merge.in0
          split.out1 ~> userFlow ~> merge.in1
          tick.outlet ~> merge.in2

          (split.in, merge.out)
      }

    Flow[FrameEvent]
      .via(Masking.unmaskIf(serverSide))
      .via(FrameHandler.create(server = serverSide))
      .mapConcat(x ⇒ x :: x :: Nil) // FIXME: #17004
      .via(bypassAndUserHandler)
      .transform(() ⇒ new FrameOutHandler(serverSide, closeTimeout))
      .via(Masking.maskIf(!serverSide, () ⇒ new SecureRandom()))
  }

  object Tick
  case object SwitchToWebsocketToken
}
