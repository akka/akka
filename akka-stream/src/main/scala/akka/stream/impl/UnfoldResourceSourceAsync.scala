/**
 * Copyright (C) 2015-2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.stream.impl

import akka.Done
import akka.annotation.InternalApi
import akka.dispatch.ExecutionContexts
import akka.stream.ActorAttributes.SupervisionStrategy
import akka.stream._
import akka.stream.impl.Stages.DefaultAttributes
import akka.stream.stage._

import scala.concurrent.{ Future, Promise }
import scala.util.Try
import scala.util.control.NonFatal

/**
 * INTERNAL API
 */
@InternalApi private[akka] final class UnfoldResourceSourceAsync[T, S](
  create:   () ⇒ Future[S],
  readData: (S) ⇒ Future[Option[T]],
  close:    (S) ⇒ Future[Done]) extends GraphStage[SourceShape[T]] {
  val out = Outlet[T]("UnfoldResourceSourceAsync.out")
  override val shape = SourceShape(out)
  override def initialAttributes: Attributes = DefaultAttributes.unfoldResourceSourceAsync

  def createLogic(inheritedAttributes: Attributes) = new GraphStageLogic(shape) with OutHandler {
    lazy val decider = inheritedAttributes.get[SupervisionStrategy].map(_.decider).getOrElse(Supervision.stoppingDecider)
    var resource = Promise[S]()
    var open = false
    implicit val context = ExecutionContexts.sameThreadExecutionContext

    setHandler(out, this)

    override def preStart(): Unit = createStream(false)

    private def createStream(withPull: Boolean): Unit = {
      val createdCallback = getAsyncCallback[Try[S]] {
        case scala.util.Success(res) ⇒
          open = true
          resource.success(res)
          if (withPull) onPull()
        case scala.util.Failure(t) ⇒ failStage(t)
      }
      try {
        create().onComplete(createdCallback.invoke)
      } catch {
        case NonFatal(ex) ⇒ failStage(ex)
      }
    }

    private def onResourceReady(f: (S) ⇒ Unit): Unit = resource.future.foreach(f)

    val errorHandler: PartialFunction[Throwable, Unit] = {
      case NonFatal(ex) ⇒ decider(ex) match {
        case Supervision.Stop ⇒
          onResourceReady(close(_))
          failStage(ex)
        case Supervision.Restart ⇒ restartState()
        case Supervision.Resume  ⇒ onPull()
      }
    }

    val readCallback = getAsyncCallback[Try[Option[T]]] {
      case scala.util.Success(data) ⇒ data match {
        case Some(d) ⇒ push(out, d)
        case None    ⇒ closeStage()
      }
      case scala.util.Failure(t) ⇒ errorHandler(t)
    }.invoke _

    final override def onPull(): Unit =
      onResourceReady { resource ⇒
        try { readData(resource).onComplete(readCallback) } catch errorHandler
      }

    override def onDownstreamFinish(): Unit = closeStage()

    private def closeAndThen(f: () ⇒ Unit): Unit = {
      setKeepGoing(true)
      val closedCallback = getAsyncCallback[Try[Done]] {
        case scala.util.Success(_) ⇒
          open = false
          f()
        case scala.util.Failure(t) ⇒
          open = false
          failStage(t)
      }

      onResourceReady(res ⇒
        try { close(res).onComplete(closedCallback.invoke) } catch {
          case NonFatal(ex) ⇒ failStage(ex)
        })
    }
    private def restartState(): Unit = closeAndThen(() ⇒ {
      resource = Promise[S]()
      createStream(true)
    })
    private def closeStage(): Unit = closeAndThen(completeStage)

    override def postStop(): Unit = {
      if (open) closeStage()
    }

  }
  override def toString = "UnfoldResourceSourceAsync"

}
