/**
 * Copyright (C) 2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.remote.artery

import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicInteger
import scala.annotation.tailrec
import scala.concurrent.Future
import scala.concurrent.Promise
import scala.concurrent.duration._
import scala.util.Failure
import scala.util.Success
import scala.util.Try
import scala.util.control.NoStackTrace
import akka.Done
import akka.stream.Attributes
import akka.stream.Inlet
import akka.stream.SinkShape
import akka.stream.stage.AsyncCallback
import akka.stream.stage.GraphStageLogic
import akka.stream.stage.GraphStageWithMaterializedValue
import akka.stream.stage.InHandler
import io.aeron.Aeron
import io.aeron.Publication
import org.agrona.concurrent.UnsafeBuffer
import org.agrona.hints.ThreadHints

object AeronSink {

  final class GaveUpSendingException(msg: String) extends RuntimeException(msg) with NoStackTrace

  private val TimerCheckPeriod = 1 << 13 // 8192
  private val TimerCheckMask = TimerCheckPeriod - 1

  private final class OfferTask(pub: Publication, var buffer: UnsafeBuffer, var msgSize: Int, onOfferSuccess: AsyncCallback[Unit],
                                giveUpAfter: Duration, onGiveUp: AsyncCallback[Unit])
    extends (() ⇒ Boolean) {
    val giveUpAfterNanos = giveUpAfter match {
      case f: FiniteDuration ⇒ f.toNanos
      case _                 ⇒ -1L
    }
    var n = 0L
    var startTime = 0L

    override def apply(): Boolean = {
      if (n == 0L) {
        // first invocation for this message
        startTime = if (giveUpAfterNanos >= 0) System.nanoTime() else 0L
      }
      n += 1
      val result = pub.offer(buffer, 0, msgSize)
      if (result >= 0) {
        n = 0L
        onOfferSuccess.invoke(())
        true
      } else if (giveUpAfterNanos >= 0 && (n & TimerCheckMask) == 0 && (System.nanoTime() - startTime) > giveUpAfterNanos) {
        // the task is invoked by the spinning thread, only check nanoTime each 8192th invocation
        n = 0L
        onGiveUp.invoke(())
        true
      } else {
        false
      }
    }
  }
}

/**
 * @param channel eg. "aeron:udp?endpoint=localhost:40123"
 */
class AeronSink(
  channel:         String,
  streamId:        Int,
  aeron:           Aeron,
  taskRunner:      TaskRunner,
  pool:            EnvelopeBufferPool,
  giveUpSendAfter: Duration,
  flightRecorder:  EventSink)
  extends GraphStageWithMaterializedValue[SinkShape[EnvelopeBuffer], Future[Done]] {
  import AeronSink._
  import TaskRunner._
  import FlightRecorderEvents._

  val in: Inlet[EnvelopeBuffer] = Inlet("AeronSink")
  override val shape: SinkShape[EnvelopeBuffer] = SinkShape(in)

  override def createLogicAndMaterializedValue(inheritedAttributes: Attributes): (GraphStageLogic, Future[Done]) = {
    val completed = Promise[Done]()
    val logic = new GraphStageLogic(shape) with InHandler {

      private var envelopeInFlight: EnvelopeBuffer = null
      private val pub = aeron.addPublication(channel, streamId)

      private var completedValue: Try[Done] = Success(Done)

      // spin between 2 to 20 depending on idleCpuLevel
      private val spinning = 2 * taskRunner.idleCpuLevel
      private var backoffCount = spinning
      private var lastMsgSize = 0
      private val offerTask = new OfferTask(pub, null, lastMsgSize, getAsyncCallback(_ ⇒ taskOnOfferSuccess()),
        giveUpSendAfter, getAsyncCallback(_ ⇒ onGiveUp()))
      private val addOfferTask: Add = Add(offerTask)

      private var offerTaskInProgress = false
      private var delegateTaskStartTime = 0L
      private var countBeforeDelegate = 0L

      private val channelMetadata = channel.getBytes("US-ASCII")

      override def preStart(): Unit = {
        setKeepGoing(true)
        pull(in)
        // TODO: Identify different sinks!
        flightRecorder.loFreq(AeronSink_Started, channelMetadata)
      }

      override def postStop(): Unit = {
        taskRunner.command(Remove(addOfferTask.task))
        flightRecorder.loFreq(AeronSink_TaskRunnerRemoved, channelMetadata)
        pub.close()
        flightRecorder.loFreq(AeronSink_PublicationClosed, channelMetadata)
        completed.complete(completedValue)
        flightRecorder.loFreq(AeronSink_Stopped, channelMetadata)
      }

      // InHandler
      override def onPush(): Unit = {
        envelopeInFlight = grab(in)
        backoffCount = spinning
        lastMsgSize = envelopeInFlight.byteBuffer.limit
        flightRecorder.hiFreq(AeronSink_EnvelopeGrabbed, lastMsgSize)
        publish()
      }

      @tailrec private def publish(): Unit = {
        val result = pub.offer(envelopeInFlight.aeronBuffer, 0, lastMsgSize)
        // FIXME handle Publication.CLOSED
        if (result < 0) {
          backoffCount -= 1
          if (backoffCount > 0) {
            ThreadHints.onSpinWait()
            publish() // recursive
          } else {
            // delegate backoff to shared TaskRunner
            offerTaskInProgress = true
            // visibility of these assignments are ensured by adding the task to the command queue
            offerTask.buffer = envelopeInFlight.aeronBuffer
            offerTask.msgSize = lastMsgSize
            delegateTaskStartTime = System.nanoTime()
            taskRunner.command(addOfferTask)
            flightRecorder.hiFreq(AeronSink_DelegateToTaskRunner, countBeforeDelegate)
          }
        } else {
          countBeforeDelegate += 1
          onOfferSuccess()
        }
      }

      private def taskOnOfferSuccess(): Unit = {
        countBeforeDelegate = 0
        flightRecorder.hiFreq(AeronSink_ReturnFromTaskRunner, System.nanoTime() - delegateTaskStartTime)
        onOfferSuccess()
      }

      private def onOfferSuccess(): Unit = {
        flightRecorder.hiFreq(AeronSink_EnvelopeOffered, lastMsgSize)
        offerTaskInProgress = false
        pool.release(envelopeInFlight)
        offerTask.buffer = null
        envelopeInFlight = null

        if (isClosed(in))
          completeStage()
        else
          pull(in)
      }

      private def onGiveUp(): Unit = {
        offerTaskInProgress = false
        val cause = new GaveUpSendingException(s"Gave up sending message to $channel after $giveUpSendAfter.")
        flightRecorder.alert(AeronSink_GaveUpEnvelope, cause.getMessage.getBytes("US-ASCII"))
        completedValue = Failure(cause)
        failStage(cause)
      }

      override def onUpstreamFinish(): Unit = {
        // flush outstanding offer before completing stage
        if (!offerTaskInProgress)
          super.onUpstreamFinish()
      }

      override def onUpstreamFailure(cause: Throwable): Unit = {
        completedValue = Failure(cause)
        super.onUpstreamFailure(cause)
      }

      setHandler(in, this)
    }

    (logic, completed.future)
  }

}
