/**
 * Copyright (C) 2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.remote.artery

import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

import scala.annotation.tailrec
import scala.concurrent.duration._

import akka.stream.Attributes
import akka.stream.Inlet
import akka.stream.SinkShape
import akka.stream.stage.AsyncCallback
import akka.stream.stage.GraphStage
import akka.stream.stage.GraphStageLogic
import akka.stream.stage.InHandler
import io.aeron.Aeron
import io.aeron.Publication
import org.agrona.concurrent.UnsafeBuffer

object AeronSink {
  type Bytes = Array[Byte]

  private def offerTask(pub: Publication, buffer: UnsafeBuffer, msgSize: AtomicInteger, onOfferSuccess: AsyncCallback[Unit]): () ⇒ Boolean = {
    var n = 0L
    var localMsgSize = -1
    () ⇒
      {
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
class AeronSink(channel: String, streamId: Int, aeron: Aeron, taskRunner: TaskRunner) extends GraphStage[SinkShape[AeronSink.Bytes]] {
  import AeronSink._
  import TaskRunner._

  val in: Inlet[Bytes] = Inlet("AeronSink")
  override val shape: SinkShape[Bytes] = SinkShape(in)

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic =
    new GraphStageLogic(shape) with InHandler {

      private val buffer = new UnsafeBuffer(ByteBuffer.allocateDirect(128 * 1024))
      private val pub = aeron.addPublication(channel, streamId)

      private val spinning = 1000
      private var backoffCount = spinning
      private var lastMsgSize = 0
      private var lastMsgSizeRef = new AtomicInteger // used in the external backoff task
      private val addOfferTask: Add = Add(offerTask(pub, buffer, lastMsgSizeRef, getAsyncCallback(_ ⇒ onOfferSuccess())))

      override def preStart(): Unit = pull(in)

      override def postStop(): Unit = {
        taskRunner.command(Remove(addOfferTask.task))
        pub.close()
      }

      // InHandler
      override def onPush(): Unit = {
        val msg = grab(in)
        buffer.putBytes(0, msg);
        backoffCount = spinning
        lastMsgSize = msg.length
        publish()
      }

      @tailrec private def publish(): Unit = {
        val result = pub.offer(buffer, 0, lastMsgSize)
        // FIXME handle Publication.CLOSED
        // TODO the backoff strategy should be measured and tuned
        if (result < 0) {
          backoffCount -= 1
          if (backoffCount > 0) {
            publish() // recursive
          } else {
            // delegate backoff to shared TaskRunner
            lastMsgSizeRef.set(lastMsgSize)
            taskRunner.command(addOfferTask)
          }
        } else {
          onOfferSuccess()
        }
      }

      private def onOfferSuccess(): Unit = {
        pull(in)
      }

      setHandler(in, this)
    }
}
