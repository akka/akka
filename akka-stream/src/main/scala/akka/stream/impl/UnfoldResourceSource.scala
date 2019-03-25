/*
 * Copyright (C) 2015-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.stream.impl

import akka.annotation.InternalApi
import akka.stream.ActorAttributes.SupervisionStrategy
import akka.stream._
import akka.stream.impl.Stages.DefaultAttributes
import akka.stream.stage._

import scala.annotation.tailrec
import scala.util.control.NonFatal

/**
 * INTERNAL API
 */
@InternalApi private[akka] final class UnfoldResourceSource[T, S](
    create: () => S,
    readData: (S) => Option[T],
    close: (S) => Unit)
    extends GraphStage[SourceShape[T]] {
  val out = Outlet[T]("UnfoldResourceSource.out")
  override val shape = SourceShape(out)
  override def initialAttributes: Attributes = DefaultAttributes.unfoldResourceSource

  def createLogic(inheritedAttributes: Attributes) = new GraphStageLogic(shape) with OutHandler {
    lazy val decider = inheritedAttributes.mandatoryAttribute[SupervisionStrategy].decider
    var open = false
    var blockingStream: S = _
    setHandler(out, this)

    override def preStart(): Unit = {
      blockingStream = create()
      open = true
    }

    @tailrec
    final override def onPull(): Unit = {
      var resumingMode = false
      try {
        readData(blockingStream) match {
          case Some(data) => push(out, data)
          case None       => closeStage()
        }
      } catch {
        case NonFatal(ex) =>
          decider(ex) match {
            case Supervision.Stop =>
              open = false
              close(blockingStream)
              failStage(ex)
            case Supervision.Restart =>
              restartState()
              resumingMode = true
            case Supervision.Resume =>
              resumingMode = true
          }
      }
      if (resumingMode) onPull()
    }

    override def onDownstreamFinish(): Unit = closeStage()

    private def restartState(): Unit = {
      open = false
      close(blockingStream)
      blockingStream = create()
      open = true
    }

    private def closeStage(): Unit =
      try {
        close(blockingStream)
        open = false
        completeStage()
      } catch {
        case NonFatal(ex) => failStage(ex)
      }

    override def postStop(): Unit = {
      if (open) close(blockingStream)
    }

  }
  override def toString = "UnfoldResourceSource"
}
