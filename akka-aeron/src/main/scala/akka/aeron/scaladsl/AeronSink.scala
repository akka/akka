/*
 * Copyright (C) 2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.aeron.scaladsl

import akka.Done
import akka.actor.ActorSystem
import akka.aeron.internal.TaskRunner.{ Add, Remove }
import akka.aeron.internal.{ OfferTask, TaskRunner }
import akka.aeron.scaladsl.AeronSink.{ GaveUpMessageException, PublicationClosedException }
import akka.aeron.{ AeronExtension, TaskRunnerExtension }
import akka.stream.scaladsl.Sink
import akka.stream.stage._
import akka.stream.{ Attributes, Inlet, SinkShape }
import akka.util.ByteString
import io.aeron.{ Aeron, Publication }
import org.agrona.concurrent.UnsafeBuffer
import org.agrona.hints.ThreadHints

import scala.annotation.tailrec
import scala.concurrent.{ Future, Promise }
import scala.util.control.NoStackTrace
import scala.util.{ Failure, Success, Try }

object AeronSink {

  // Not shared with Artery as these are public API for the public AeronSink
  final class PublicationClosedException(message: String) extends RuntimeException(message) with NoStackTrace
  final class GaveUpMessageException(msg: String) extends RuntimeException(msg) with NoStackTrace

  // TODO should this be tied to a specific actor system?
  // TODO allow passing in own Aeron instance?
  def apply(system: ActorSystem, settings: AeronSinkSettings): Sink[ByteString, Future[Done]] =
    Sink.fromGraph(new AeronSink(AeronExtension(system).aeron, settings, TaskRunnerExtension(system).taskRunner))

}

class AeronSink private (aeron: Aeron, settings: AeronSinkSettings, taskRunner: TaskRunner) extends GraphStageWithMaterializedValue[SinkShape[ByteString], Future[Done]] {

  private val in: Inlet[ByteString] = Inlet("AeronSink")
  private val buffer = new UnsafeBuffer()
  private val completed = Promise[Done]

  override def shape: SinkShape[ByteString] = SinkShape(in)

  override def createLogicAndMaterializedValue(inheritedAttributes: Attributes): (GraphStageLogic, Future[Done]) = {
    val logic = new GraphStageLogicWithLogging(shape) with InHandler {

      private var inFlight: ByteString = null

      private var completedValue: Try[Done] = Success(Done)
      private val publication = aeron.addPublication(settings.channel, settings.streamId)
      // Lower spinning value will result in less CPU but more overhead to go via the TaskRunner thread
      private val spinning = 2 * taskRunner.idleCpuLevel
      // How many times to try and publish before delegating to the TaskRunner
      private var backoffCount = spinning
      private var offerTaskInProgress = false

      private val offerTask = new OfferTask(publication, null, 0, getAsyncCallback(_ ⇒ onOfferSuccess()),
        settings.giveUpAfter, getAsyncCallback(_ ⇒ onGiveUp()), getAsyncCallback(_ ⇒ onPublicationClosed()))

      private val addOfferTask: Add = Add(offerTask)

      override def preStart(): Unit = {
        pull(in)
      }

      override def onPush(): Unit = {
        require(inFlight == null)
        inFlight = grab(in)
        backoffCount = spinning
        publish()
      }

      @tailrec private def publish(): Unit = {
        // TODO DirectBuffer impl backed by a ByteString rather than copying into an UnsafeBuffer?
        // Maybe not worth it as this doesn't copy
        buffer.wrap(inFlight.asByteBuffer)
        val result = publication.offer(buffer, 0, inFlight.size)
        if (result < 0) {
          if (result == Publication.CLOSED)
            onPublicationClosed()
          else if (result == Publication.NOT_CONNECTED)
            delegateBackoff()
          else {
            backoffCount -= 1
            if (backoffCount > 0) {
              ThreadHints.onSpinWait()
              publish() // recursive
            } else
              delegateBackoff()
          }
        } else {
          onOfferSuccess()
        }
      }

      private def delegateBackoff(): Unit = {
        // delegate backoff to shared TaskRunner
        offerTaskInProgress = true
        // visibility of these assignments are ensured by adding the task to the command queue
        offerTask.buffer = buffer
        offerTask.msgSize = inFlight.size
        taskRunner.command(addOfferTask)
      }

      private def onGiveUp(): Unit = {
        offerTaskInProgress = false
        val cause = new GaveUpMessageException(s"Gave up sending message to ${settings.channel} after ${settings.giveUpAfter}.")
        completedValue = Failure(cause)
        failStage(cause)
      }

      private def onPublicationClosed(): Unit = {
        offerTaskInProgress = false
        val cause = new PublicationClosedException(s"Aeron Publication to [${settings.channel}] was closed.")
        // this is not expected, since we didn't close the publication ourselves
        completedValue = Failure(cause)
        failStage(cause)
      }

      private def onOfferSuccess(): Unit = {
        offerTaskInProgress = false
        offerTask.buffer = null
        inFlight = null
        if (isClosed(in))
          completeStage()
        else
          pull(in)
      }

      override def onUpstreamFailure(cause: Throwable): Unit = {
        completedValue = Failure(cause)
        super.onUpstreamFailure(cause)
      }

      override def onUpstreamFinish(): Unit = {
        // flush outstanding offer before completing stage
        if (!offerTaskInProgress)
          super.onUpstreamFinish()
      }

      override def postStop(): Unit = {
        try {
          taskRunner.command(Remove(addOfferTask.task))
          publication.close()
        } finally {
          completed.complete(completedValue)
        }
      }

      setHandler(in, this)
    }

    (logic, completed.future)
  }

}
