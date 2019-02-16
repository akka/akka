/*
 * Copyright (C) 2015-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.stream.impl

import akka.Done
import akka.annotation.InternalApi
import akka.stream.ActorAttributes.SupervisionStrategy
import akka.stream._
import akka.stream.impl.Stages.DefaultAttributes
import akka.stream.stage._

import scala.concurrent.{ Future, Promise }
import scala.util.control.NonFatal

/**
 * INTERNAL API
 */
@InternalApi private[akka] final class UnfoldResourceSink[T, S](
  create: () ⇒ S,
  write:  (S, T) ⇒ Unit,
  close:  (S) ⇒ Unit) extends GraphStageWithMaterializedValue[SinkShape[T], Future[Done]] {
  val in = Inlet[T]("UnfoldResourceSink.in")
  override val shape = SinkShape(in)
  override def initialAttributes: Attributes = DefaultAttributes.unfoldResourceSink

  override def createLogicAndMaterializedValue(inheritedAttributes: Attributes): (GraphStageLogic, Future[Done]) = {
    val done = Promise[Done]()

    val createLogic = new GraphStageLogic(shape) with InHandler with StageLogging {
      lazy val decider = inheritedAttributes.mandatoryAttribute[SupervisionStrategy].decider
      var blockingStream = Option.empty[S]
      setHandler(in, this)

      override def preStart(): Unit = {
        try {
          blockingStream = Some(create())
        } catch {
          case NonFatal(ex) ⇒
            done.tryFailure(ex)
            throw ex
        }

        if (!isClosed(in)) {
          pull(in)
        } else {
          closeStage()
        }
      }

      final override def onPush(): Unit = {
        try {
          blockingStream.foreach(write(_, grab(in)))
        } catch {
          case NonFatal(ex) ⇒
            decider(ex) match {
              case Supervision.Stop ⇒
                val s = blockingStream
                blockingStream = None
                try {
                  s.foreach(close)
                } catch {
                  case NonFatal(ex2) ⇒
                    log.error("Error closing resource while handling error writing; throwing write error", ex2)
                }
                done.tryFailure(ex)
                failStage(ex)
              case Supervision.Restart ⇒
                restartState()
              case Supervision.Resume ⇒
            }
        }
        if (!isClosed(in)) {
          pull(in)
        } else {
          closeStage()
        }
      }

      override def onUpstreamFinish(): Unit = closeStage()

      private def restartState(): Unit = {
        val s = blockingStream
        blockingStream = None

        try {
          s.foreach(close)
        } catch {
          case NonFatal(ex) ⇒
            done.tryFailure(ex)
            failStage(ex)
        }

        try {
          blockingStream = Some(create())
        } catch {
          case NonFatal(ex) ⇒
            done.tryFailure(ex)
            failStage(ex)
        }
      }

      private def closeStage(): Unit =
        try {
          val s = blockingStream
          blockingStream = None
          s.foreach(close)
          done.trySuccess(Done)
          completeStage()
        } catch {
          case NonFatal(ex) ⇒
            done.tryFailure(ex)
            failStage(ex)
        }

      override def postStop(): Unit = {
        blockingStream.foreach(close)
      }
    }

    (createLogic, done.future)
  }

  override def toString = "UnfoldResourceSink"
}
