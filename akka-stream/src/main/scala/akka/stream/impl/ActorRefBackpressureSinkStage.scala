/*
 * Copyright (C) 2015-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.stream.impl

import java.util

import akka.actor._
import akka.annotation.InternalApi
import akka.stream.impl.Stages.DefaultAttributes
import akka.stream._
import akka.stream.Attributes.InputBuffer
import akka.stream.stage._

/**
 * INTERNAL API
 */
@InternalApi private[akka] class ActorRefBackpressureSinkStage[In](
  ref:               ActorRef,
  messageAdapter:    ActorRef ⇒ In ⇒ Any,
  onInitMessage:     ActorRef ⇒ Any,
  ackMessage:        Any,
  onCompleteMessage: Any,
  onFailureMessage:  (Throwable) ⇒ Any)
  extends GraphStage[SinkShape[In]] {
  val in: Inlet[In] = Inlet[In]("ActorRefBackpressureSink.in")
  override def initialAttributes = DefaultAttributes.actorRefWithAck
  override val shape: SinkShape[In] = SinkShape(in)

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic =
    new GraphStageLogic(shape) with InHandler {
      implicit def self: ActorRef = stageActor.ref

      val maxBuffer = inheritedAttributes.get[InputBuffer](InputBuffer(16, 16)).max
      require(maxBuffer > 0, "Buffer size must be greater than 0")

      val buffer: util.Deque[In] = new util.ArrayDeque[In]()
      var acknowledgementReceived = false
      var completeReceived = false
      var completionSignalled = false

      private def receive(evt: (ActorRef, Any)): Unit = {
        evt._2 match {
          case `ackMessage` ⇒ {
            if (buffer.isEmpty) acknowledgementReceived = true
            else {
              // onPush might have filled the buffer up and
              // stopped pulling, so we pull here
              if (buffer.size() == maxBuffer) tryPull(in)
              dequeueAndSend()
            }
          }
          case Terminated(`ref`) ⇒ completeStage()
          case _                 ⇒ //ignore all other messages
        }
      }

      override def preStart() = {
        setKeepGoing(true)
        getStageActor(receive).watch(ref)
        ref ! onInitMessage(self)
        pull(in)
      }

      private def dequeueAndSend(): Unit = {
        ref ! messageAdapter(self)(buffer.poll())
        if (buffer.isEmpty && completeReceived) finish()
      }

      private def finish(): Unit = {
        ref ! onCompleteMessage
        completionSignalled = true
        completeStage()
      }

      def onPush(): Unit = {
        buffer offer grab(in)
        if (acknowledgementReceived) {
          dequeueAndSend()
          acknowledgementReceived = false
        }
        if (buffer.size() < maxBuffer) pull(in)
      }

      override def onUpstreamFinish(): Unit = {
        if (buffer.isEmpty) finish()
        else completeReceived = true
      }

      override def onUpstreamFailure(ex: Throwable): Unit = {
        ref ! onFailureMessage(ex)
        completionSignalled = true
        failStage(ex)
      }

      override def postStop(): Unit = {
        if (!completionSignalled) {
          ref ! onFailureMessage(new AbruptStageTerminationException(this))
        }
      }

      setHandler(in, this)
    }

  override def toString = "ActorRefBackpressureSink"
}
