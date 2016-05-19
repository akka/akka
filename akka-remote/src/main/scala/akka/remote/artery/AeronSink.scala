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

object AeronSink {

  class OfferTask(pub: Publication, var buffer: UnsafeBuffer, msgSize: AtomicInteger, onOfferSuccess: AsyncCallback[Unit])
    extends (() ⇒ Boolean) {

    var n = 0L
    var localMsgSize = -1

    override def apply(): Boolean = {
      n += 1
      if (localMsgSize == -1)
        localMsgSize = msgSize.get
      val result = pub.offer(buffer, 0, localMsgSize)
      if (result >= 0) {
        n = 0
        localMsgSize = -1
        onOfferSuccess.invoke(())
        true
      } else {
        // FIXME drop after too many attempts?
        if (n > 1000000 && n % 100000 == 0)
          println(s"# offer not accepted after $n") // FIXME
        false
      }
    }
  }
}

/**
 * @param channel eg. "aeron:udp?endpoint=localhost:40123"
 */
class AeronSink(channel: String, streamId: Int, aeron: Aeron, taskRunner: TaskRunner, pool: EnvelopeBufferPool)
  extends GraphStageWithMaterializedValue[SinkShape[EnvelopeBuffer], Future[Done]] {
  import AeronSink._
  import TaskRunner._

  val in: Inlet[EnvelopeBuffer] = Inlet("AeronSink")
  override val shape: SinkShape[EnvelopeBuffer] = SinkShape(in)

  override def createLogicAndMaterializedValue(inheritedAttributes: Attributes): (GraphStageLogic, Future[Done]) = {
    val completed = Promise[Done]()
    val logic = new GraphStageLogic(shape) with InHandler {

      private var envelopeInFlight: EnvelopeBuffer = null
      private val pub = aeron.addPublication(channel, streamId)

      private var completedValue: Try[Done] = Success(Done)

      private val spinning = 1000
      private var backoffCount = spinning
      private var lastMsgSize = 0
      private val lastMsgSizeRef = new AtomicInteger // used in the external backoff task
      private val offerTask = new OfferTask(pub, null, lastMsgSizeRef, getAsyncCallback(_ ⇒ onOfferSuccess()))
      private val addOfferTask: Add = Add(offerTask)

      private var offerTaskInProgress = false

      override def preStart(): Unit = {
        setKeepGoing(true)
        pull(in)
      }

      override def postStop(): Unit = {
        taskRunner.command(Remove(addOfferTask.task))
        pub.close()
        completed.complete(completedValue)
      }

      // InHandler
      override def onPush(): Unit = {
        envelopeInFlight = grab(in)
        backoffCount = spinning
        lastMsgSize = envelopeInFlight.byteBuffer.limit
        publish()
      }

      @tailrec private def publish(): Unit = {
        val result = pub.offer(envelopeInFlight.aeronBuffer, 0, lastMsgSize)
        // FIXME handle Publication.CLOSED
        // TODO the backoff strategy should be measured and tuned
        if (result < 0) {
          backoffCount -= 1
          if (backoffCount > 0) {
            publish() // recursive
          } else {
            // delegate backoff to shared TaskRunner
            lastMsgSizeRef.set(lastMsgSize)
            offerTaskInProgress = true
            offerTask.buffer = envelopeInFlight.aeronBuffer
            taskRunner.command(addOfferTask)
          }
        } else {
          onOfferSuccess()
        }
      }

      private def onOfferSuccess(): Unit = {
        offerTaskInProgress = false
        pool.release(envelopeInFlight)
        offerTask.buffer = null
        envelopeInFlight = null

        if (isClosed(in))
          completeStage()
        else
          pull(in)
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
