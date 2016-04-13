package akka.aeron

import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

import scala.annotation.tailrec
import scala.concurrent.duration._

import akka.stream.Attributes
import akka.stream.Outlet
import akka.stream.SourceShape
import akka.stream.stage.GraphStage
import akka.stream.stage.GraphStageLogic
import akka.stream.stage.OutHandler
import akka.stream.stage.TimerGraphStageLogic
import io.aeron.Aeron
import io.aeron.logbuffer.FragmentHandler
import io.aeron.logbuffer.Header
import org.agrona.DirectBuffer
import org.agrona.concurrent.BackoffIdleStrategy
import org.agrona.concurrent.UnsafeBuffer

object AeronSource {
  type Bytes = Array[Byte]
  private case object Backoff
}

/**
 * @param channel eg. "aeron:udp?endpoint=localhost:40123"
 */
class AeronSource(channel: String, aeron: () => Aeron) extends GraphStage[SourceShape[AeronSource.Bytes]] {
  import AeronSource._

  val out: Outlet[Bytes] = Outlet("AeronSource")
  override val shape: SourceShape[Bytes] = SourceShape(out)

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic =
    new TimerGraphStageLogic(shape) with OutHandler {

      private val buffer = new UnsafeBuffer(ByteBuffer.allocateDirect(256))
      private val streamId = 10
      private val sub = aeron().addSubscription(channel, streamId)
      private val running = new AtomicBoolean(true)
      private val idleStrategy = new BackoffIdleStrategy(
        100, 10, TimeUnit.MICROSECONDS.toNanos(1), TimeUnit.MICROSECONDS.toNanos(100))
      private val retries = 115
      private var backoffCount = retries

      val receiveMessage = getAsyncCallback[Bytes] { data =>
        push(out, data)
      }

      val fragmentHandler: FragmentHandler = new FragmentHandler {
        override def onFragment(buffer: DirectBuffer, offset: Int, length: Int, header: Header): Unit = {
          val data = Array.ofDim[Byte](length)
          buffer.getBytes(offset, data);
          receiveMessage.invoke(data)
        }
      }

      override def postStop(): Unit = {
        running.set(false)
        sub.close()
      }

      // OutHandler
      override def onPull(): Unit = {
        idleStrategy.reset()
        backoffCount = retries
        subscriberLoop()
      }

      @tailrec private def subscriberLoop(): Unit =
        if (running.get) {
          val fragmentsRead = sub.poll(fragmentHandler, 1)
          if (fragmentsRead <= 0) {
            // TODO the backoff strategy should be measured and tuned
            if (backoffCount <= 0) {
              // TODO Instead of using the scheduler we should handoff the task of
              // retrying/polling to a separate thread that performs the polling for
              // all sources/sinks and notifies back when there is some news.
              // println(s"# scheduled backoff ${0 - backoffCount + 1}") // FIXME
              backoffCount -= 1
              if (backoffCount <= -5)
                scheduleOnce(Backoff, 50.millis)
              else
                scheduleOnce(Backoff, 1.millis)
            } else {
              idleStrategy.idle()
              backoffCount -= 1
              subscriberLoop() // recursive
            }
          }
        }

      override protected def onTimer(timerKey: Any): Unit = {
        timerKey match {
          case Backoff => subscriberLoop()
          case msg     => super.onTimer(msg)
        }
      }

      setHandler(out, this)
    }
}
