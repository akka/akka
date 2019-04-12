/*
 * Copyright (C) 2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.stream.impl

import akka.actor.{ ActorRef, Terminated }
import akka.annotation.InternalApi
import akka.stream.{ Attributes, Inlet, SinkShape }
import akka.stream.stage.{ GraphStage, GraphStageLogic, InHandler }

/**
 * INTERNAL API
 */
@InternalApi private[akka] class ActorRefSink[T](
    ref: ActorRef,
    onCompleteMessage: Any,
    onFailureMessage: Throwable => Any)
    extends GraphStage[SinkShape[T]] {

  val in: Inlet[T] = Inlet[T]("ActorRefSink.in")

  override val shape: SinkShape[T] = SinkShape(in)

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic = new GraphStageLogic(shape) {
    private implicit def self: ActorRef = stageActor.ref

    override def preStart(): Unit = {
      super.preStart()
      getStageActor({
        case (_, Terminated(`ref`)) => completeStage()
        case _                      => //ignore all other messages
      }).watch(ref)
      pull(in)
    }

    setHandler(
      in,
      new InHandler {
        override def onPush(): Unit = {
          val e = grab(in)
          ref ! e
          tryPull(in)
        }

        override def onUpstreamFinish(): Unit = {
          ref ! onCompleteMessage
          completeStage()
        }

        override def onUpstreamFailure(ex: Throwable): Unit = {
          ref ! onFailureMessage(ex)
          failStage(ex)
        }
      })
  }
}
