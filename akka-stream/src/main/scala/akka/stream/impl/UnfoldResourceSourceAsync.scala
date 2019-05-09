/*
 * Copyright (C) 2015-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.stream.impl

import akka.Done
import akka.annotation.InternalApi
import akka.dispatch.ExecutionContexts.sameThreadExecutionContext
import akka.stream.ActorAttributes.SupervisionStrategy
import akka.stream._
import akka.stream.impl.Stages.DefaultAttributes
import akka.stream.stage._

import scala.concurrent.Future
import scala.util.{ Failure, Success, Try }
import scala.util.control.NonFatal

/**
 * INTERNAL API
 */
@InternalApi private[akka] final class UnfoldResourceSourceAsync[T, S](
    create: () => Future[S],
    readData: (S) => Future[Option[T]],
    close: (S) => Future[Done])
    extends GraphStage[SourceShape[T]] {
  val out = Outlet[T]("UnfoldResourceSourceAsync.out")
  override val shape = SourceShape(out)
  override def initialAttributes: Attributes = DefaultAttributes.unfoldResourceSourceAsync

  def createLogic(inheritedAttributes: Attributes) = new GraphStageLogic(shape) with OutHandler {
    lazy val decider = inheritedAttributes.mandatoryAttribute[SupervisionStrategy].decider
    private implicit def ec = materializer.executionContext
    private var state: Option[S] = None

    private val createdCallback = getAsyncCallback[Try[S]] {
      case Success(resource) =>
        state = Some(resource)
        if (isAvailable(out)) onPull()
      case Failure(t) => failStage(t)
    }.invokeWithFeedback _

    private val errorHandler: PartialFunction[Throwable, Unit] = {
      case NonFatal(ex) =>
        decider(ex) match {
          case Supervision.Stop =>
            failStage(ex)
          case Supervision.Restart => restartResource()
          case Supervision.Resume  => onPull()
        }
    }

    private val readCallback = getAsyncCallback[Try[Option[T]]] {
      case Success(data) =>
        data match {
          case Some(d) => push(out, d)
          case None    =>
            // end of resource reached, lets close it
            state match {
              case Some(resource) =>
                close(resource).onComplete(getAsyncCallback[Try[Done]] {
                  case Success(Done) => completeStage()
                  case Failure(ex)   => failStage(ex)
                }.invoke)
                state = None

              case None =>
                // cannot happen, but for good measure
                throw new IllegalStateException("Reached end of data but there is no open resource")
            }
        }
      case Failure(t) => errorHandler(t)
    }.invoke _

    override def preStart(): Unit = createResource()

    override def onPull(): Unit =
      state match {
        case Some(resource) =>
          try {
            readData(resource).onComplete(readCallback)(sameThreadExecutionContext)
          } catch errorHandler
        case None =>
        // we got a pull but there is no open resource, we are either
        // currently creating/restarting then the read will be triggered when creating the
        // resource completes, or shutting down and then the pull does not matter anyway
      }

    override def postStop(): Unit = {
      state.foreach(r => close(r))
    }

    private def restartResource(): Unit = {
      state match {
        case Some(resource) =>
          // wait for the resource to close before restarting
          close(resource).onComplete(getAsyncCallback[Try[Done]] {
            case Success(Done) =>
              createResource()
            case Failure(ex) => failStage(ex)
          }.invoke)
          state = None
        case None =>
          createResource()
      }
    }

    private def createResource(): Unit = {
      create().onComplete { resource =>
        createdCallback(resource).failed.foreach {
          case _: StreamDetachedException =>
            // stream stopped
            resource match {
              case Success(r) =>
                close(r)
              case Failure(ex) => throw ex // failed to open but stream is stopped already
            }
        }
      }
    }

    setHandler(out, this)

  }
  override def toString = "UnfoldResourceSourceAsync"

}
