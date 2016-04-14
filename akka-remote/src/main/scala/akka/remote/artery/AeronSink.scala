package akka.remote.artery

import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit

import scala.annotation.tailrec
import scala.concurrent.duration._

import akka.stream.Attributes
import akka.stream.Inlet
import akka.stream.SinkShape
import akka.stream.stage.GraphStage
import akka.stream.stage.GraphStageLogic
import akka.stream.stage.InHandler
import akka.stream.stage.TimerGraphStageLogic
import io.aeron.Aeron
import org.agrona.concurrent.BackoffIdleStrategy
import org.agrona.concurrent.UnsafeBuffer

object AeronSink {
  type Bytes = Array[Byte]
  private case object Backoff
}

/**
 * @param channel eg. "aeron:udp?endpoint=localhost:40123"
 */
class AeronSink(channel: String, aeron: () ⇒ Aeron) extends GraphStage[SinkShape[AeronSink.Bytes]] {
  import AeronSink._

  val in: Inlet[Bytes] = Inlet("AeronSink")
  override val shape: SinkShape[Bytes] = SinkShape(in)

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic =
    new TimerGraphStageLogic(shape) with InHandler {

      private val buffer = new UnsafeBuffer(ByteBuffer.allocateDirect(128 * 1024))
      private val streamId = 10
      private val pub = aeron().addPublication(channel, streamId)
      private val idleStrategy = new BackoffIdleStrategy(
        100, 10, TimeUnit.MICROSECONDS.toNanos(1), TimeUnit.MICROSECONDS.toNanos(100))
      private val retries = 120

      private var backoffCount = retries
      private var lastMsgSize = 0

      override def preStart(): Unit = pull(in)

      override def postStop(): Unit = {
        pub.close()
      }

      // InHandler
      override def onPush(): Unit = {
        val msg = grab(in)
        buffer.putBytes(0, msg);
        idleStrategy.reset()
        backoffCount = retries
        lastMsgSize = msg.length
        publish()
      }

      @tailrec private def publish(): Unit = {
        val result = pub.offer(buffer, 0, lastMsgSize)
        // FIXME handle Publication.CLOSED
        // TODO the backoff strategy should be measured and tuned
        if (result < 0) {
          if (backoffCount == 1) {
            println(s"# drop") // FIXME
            pull(in) // drop it
          } else if (backoffCount <= 5) {
            // TODO Instead of using the scheduler we should handoff the task of
            // retrying/polling to a separate thread that performs the polling for
            // all sources/sinks and notifies back when there is some news.
            // println(s"# scheduled backoff ${6 - backoffCount}") // FIXME
            backoffCount -= 1
            if (backoffCount <= 2)
              scheduleOnce(Backoff, 50.millis)
            else
              scheduleOnce(Backoff, 1.millis)
          } else {
            idleStrategy.idle()
            backoffCount -= 1
            publish() // recursive
          }
        } else {
          pull(in)
        }
      }

      override protected def onTimer(timerKey: Any): Unit = {
        timerKey match {
          case Backoff ⇒ publish()
          case msg     ⇒ super.onTimer(msg)
        }
      }

      setHandler(in, this)
    }
}
