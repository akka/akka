package akka.remote.artery

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
import io.aeron.FragmentAssembler
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
class AeronSource(channel: String, aeron: Aeron) extends GraphStage[SourceShape[AeronSource.Bytes]] {
  import AeronSource._

  val out: Outlet[Bytes] = Outlet("AeronSource")
  override val shape: SourceShape[Bytes] = SourceShape(out)

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic =
    new TimerGraphStageLogic(shape) with OutHandler {

      private val buffer = new UnsafeBuffer(ByteBuffer.allocateDirect(128 * 1024))
      private val streamId = 10
      private val sub = aeron.addSubscription(channel, streamId)
      private val running = new AtomicBoolean(true)
      private val spinning = 20000
      private val yielding = 0
      private val parking = 50
      private val idleStrategy = new BackoffIdleStrategy(
        spinning, yielding, TimeUnit.MICROSECONDS.toNanos(1), TimeUnit.MICROSECONDS.toNanos(100))
      private val idleStrategyRetries = spinning + yielding + parking
      private var backoffCount = idleStrategyRetries
      private val backoffDuration1 = 1.millis
      private val backoffDuration2 = 50.millis
      private var messageReceived = false

      // the fragmentHandler is called from `poll` in same thread, i.e. no async callback is needed
      val fragmentHandler = new FragmentAssembler(new FragmentHandler {
        override def onFragment(buffer: DirectBuffer, offset: Int, length: Int, header: Header): Unit = {
          messageReceived = true
          val data = Array.ofDim[Byte](length)
          buffer.getBytes(offset, data);
          push(out, data)
        }
      })

      override def postStop(): Unit = {
        running.set(false)
        sub.close()
      }

      // OutHandler
      override def onPull(): Unit = {
        idleStrategy.reset()
        backoffCount = idleStrategyRetries
        subscriberLoop()
      }

      @tailrec private def subscriberLoop(): Unit =
        if (running.get) {
          messageReceived = false // will be set by the fragmentHandler if got full msg
          // we only poll 1 fragment, otherwise we would have to use another buffer for
          // received messages that can't be pushed
          val fragmentsRead = sub.poll(fragmentHandler, 1)
          if (fragmentsRead > 0 && !messageReceived)
            subscriberLoop() // recursive, read more fragments
          else if (fragmentsRead <= 0) {
            // TODO the backoff strategy should be measured and tuned
            backoffCount -= 1
            if (backoffCount > 0) {
              idleStrategy.idle()
              subscriberLoop() // recursive
            } else if (backoffCount > -1000) {
              // TODO Instead of using the scheduler we should handoff the task of
              // retrying/polling to a separate thread that performs the polling for
              // all sources/sinks and notifies back when there is some news.
              scheduleOnce(Backoff, backoffDuration1)
            } else {
              scheduleOnce(Backoff, backoffDuration2)
            }
          }
        }

      override protected def onTimer(timerKey: Any): Unit = {
        timerKey match {
          case Backoff ⇒ subscriberLoop()
          case msg     ⇒ super.onTimer(msg)
        }
      }

      setHandler(out, this)
    }
}
