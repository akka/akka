/**
 * Copyright (C) 2015 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.impl

import java.util

import akka.actor._
import akka.dispatch.sysmsg.{ DeathWatchNotification, SystemMessage, Watch }
import akka.stream.stage.GraphStageLogic.StageActorRef
import akka.stream.{ Inlet, SinkShape, ActorMaterializer, Attributes }
import akka.stream.Attributes.InputBuffer
import akka.stream.stage._

/**
 * INTERNAL API
 */
private[akka] class ActorRefBackpressureSinkStage[In](ref: ActorRef, onInitMessage: Any,
                                                      ackMessage: Any,
                                                      onCompleteMessage: Any,
                                                      onFailureMessage: (Throwable) ⇒ Any)
  extends GraphStage[SinkShape[In]] {
  val in: Inlet[In] = Inlet[In]("ActorRefBackpressureSink.in")
  override val shape: SinkShape[In] = SinkShape(in)

  val maxBuffer = module.attributes.getAttribute(classOf[InputBuffer], InputBuffer(16, 16)).max
  require(maxBuffer > 0, "Buffer size must be greater than 0")

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic =
    new GraphStageLogic(shape) {
      implicit var self: StageActorRef = _

      val buffer: util.Deque[In] = new util.ArrayDeque[In]()
      var acknowledgementReceived = false
      var completeReceived = false

      override def keepGoingAfterAllPortsClosed: Boolean = true

      private val callback: AsyncCallback[Unit] = getAsyncCallback((_: Unit) ⇒ {
        if (!buffer.isEmpty) sendData()
        else acknowledgementReceived = true
      })

      private val deathWatchCallback: AsyncCallback[Unit] =
        getAsyncCallback((Unit) ⇒ completeStage())

      private def receive(evt: (ActorRef, Any)): Unit = {
        evt._2 match {
          case `ackMessage`      ⇒ callback.invoke(())
          case Terminated(`ref`) ⇒ deathWatchCallback.invoke(())
          case _                 ⇒ //ignore all other messages
        }
      }

      override def preStart() = {
        self = getStageActorRef(receive)
        self.watch(ref)
        ref ! onInitMessage
        pull(in)
      }

      private def sendData(): Unit = {
        if (!buffer.isEmpty) {
          ref ! buffer.poll()
          acknowledgementReceived = false
        }
        if (buffer.isEmpty && completeReceived) finish()
      }

      private def finish(): Unit = {
        ref ! onCompleteMessage
        completeStage()
      }

      setHandler(in, new InHandler {
        override def onPush(): Unit = {
          buffer offer grab(in)
          if (acknowledgementReceived) sendData()
          if (buffer.size() < maxBuffer) pull(in)
        }
        override def onUpstreamFinish(): Unit = {
          if (buffer.isEmpty) finish()
          else completeReceived = true
        }
        override def onUpstreamFailure(ex: Throwable): Unit = {
          ref ! onFailureMessage(ex)
          failStage(ex)
        }
      })
    }

  override def toString = "ActorRefBackpressureSink"
}
