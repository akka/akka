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

import scala.concurrent.{ Future, Promise }
import scala.util.{ Failure, Success, Try }
import scala.util.control.NonFatal

/**
 * INTERNAL API
 */
@InternalApi private[akka] final class UnfoldResourceSinkAsync[T, S](
  create: () ⇒ Future[S],
  write:  (S, T) ⇒ Future[Unit],
  close:  (S) ⇒ Future[Unit]) extends GraphStageWithMaterializedValue[SinkShape[T], Future[Done]] {
  val in = Inlet[T]("UnfoldResourceSinkAsync.in")
  override val shape = SinkShape(in)
  override def initialAttributes: Attributes = DefaultAttributes.unfoldResourceSinkAsync

  override def createLogicAndMaterializedValue(inheritedAttributes: Attributes): (GraphStageLogic, Future[Done]) = {
    val promise = Promise[Done]()

    val createLogic = new GraphStageLogic(shape) with InHandler {
      lazy val decider = inheritedAttributes.mandatoryAttribute[SupervisionStrategy].decider

      private implicit def ec = materializer.executionContext

      private var blockingStream: Option[S] = None

      private val createdCallback = getAsyncCallback[Try[S]] {
        case Success(resource) ⇒
          blockingStream = Some(resource)

          if (!isClosed(in)) {
            pull(in)
          }
        case Failure(t) ⇒ failStage(t)
      }.invokeWithFeedback _

      private val errorHandler: PartialFunction[Throwable, Unit] = {
        case NonFatal(ex) ⇒ decider(ex) match {
          case Supervision.Stop ⇒
            promise.tryFailure(ex)
            failStage(ex)
          case Supervision.Restart ⇒ restartResource()
          case Supervision.Resume  ⇒ pull(in)
        }
      }

      private val pushCallback = getAsyncCallback[Try[Unit]] {
        case Success(_) ⇒ pull(in)
        case Failure(t) ⇒ errorHandler(t)
      }.invoke _

      override def preStart(): Unit = createResource()

      override def onPush(): Unit =
        blockingStream match {
          case Some(resource) ⇒
            try {
              write(resource, grab(in)).onComplete(pushCallback)(sameThreadExecutionContext)
            } catch errorHandler
          case None ⇒
          // we got a pull but there is no open resource, we are either
          // currently creating/restarting then the pull will be triggered when creating the
          // resource completes, or shutting down and then the push does not matter anyway
        }

      override def postStop(): Unit = {
        val o = blockingStream
        blockingStream = None
        o match {
          case Some(s) ⇒
            try {
              val f = close(s).map(_ ⇒ Done)
              f.failed.foreach(failStage)
              promise.tryCompleteWith(f)
            } catch {
              case NonFatal(ex) ⇒
                promise.tryFailure(ex)
                failStage(ex)
            }
          case None ⇒ promise.trySuccess(Done)
        }
      }

      private def restartResource(): Unit = {
        val o = blockingStream
        blockingStream = None

        o match {
          case Some(resource) ⇒
            // wait for the resource to close before restarting
            try {
              close(resource).onComplete(getAsyncCallback[Try[Unit]] {
                case Success(_) ⇒
                  createResource()
                case Failure(ex) ⇒
                  promise.tryFailure(ex)
                  failStage(ex)

              }.invoke)
            } catch {
              case NonFatal(ex) ⇒
                promise.tryFailure(ex)
                failStage(ex)
            }

          case None ⇒ createResource()
        }
      }

      private def createResource(): Unit = {
        try {
          create().onComplete { resource ⇒
            createdCallback(resource).recover {
              case _: StreamDetachedException ⇒
                // stream stopped
                resource match {
                  case Success(r)  ⇒ close(r)
                  case Failure(ex) ⇒ throw ex // failed to open but stream is stopped already
                }
            }
          }
        } catch {
          case NonFatal(ex) ⇒
            promise.tryFailure(ex)
            failStage(ex)
        }
      }

      setHandler(in, this)
    }

    (createLogic, promise.future)
  }

  override def toString = "UnfoldResourceSinkAsync"

}
