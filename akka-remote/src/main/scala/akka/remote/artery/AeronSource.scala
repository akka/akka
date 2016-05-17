/**
 * Copyright (C) 2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.remote.artery

import java.util.concurrent.TimeUnit

import scala.annotation.tailrec
import scala.concurrent.duration._

import akka.stream.Attributes
import akka.stream.Outlet
import akka.stream.SourceShape
import akka.stream.stage.AsyncCallback
import akka.stream.stage.GraphStage
import akka.stream.stage.GraphStageLogic
import akka.stream.stage.OutHandler
import io.aeron.Aeron
import io.aeron.FragmentAssembler
import io.aeron.Subscription
import io.aeron.logbuffer.FragmentHandler
import io.aeron.logbuffer.Header
import org.agrona.DirectBuffer
import org.agrona.concurrent.BackoffIdleStrategy

object AeronSource {
  type Bytes = Array[Byte]

  private def pollTask(sub: Subscription, handler: MessageHandler, onMessage: AsyncCallback[Bytes]): () ⇒ Boolean = {
    () ⇒
      {
        handler.reset
        val fragmentsRead = sub.poll(handler.fragmentsHandler, 1)
        val msg = handler.messageReceived
        handler.reset() // for GC
        if (msg ne null) {
          onMessage.invoke(msg)
          true
        } else
          false
      }
  }

  class MessageHandler {
    def reset(): Unit = messageReceived = null

    var messageReceived: Bytes = null

    val fragmentsHandler = new Fragments(data ⇒ messageReceived = data)
  }

  class Fragments(onMessage: Bytes ⇒ Unit) extends FragmentAssembler(new FragmentHandler {
    override def onFragment(buffer: DirectBuffer, offset: Int, length: Int, header: Header): Unit = {
      val data = Array.ofDim[Byte](length)
      buffer.getBytes(offset, data)
      onMessage(data)
    }
  })
}

/**
 * @param channel eg. "aeron:udp?endpoint=localhost:40123"
 */
class AeronSource(channel: String, streamId: Int, aeron: Aeron, taskRunner: TaskRunner) extends GraphStage[SourceShape[AeronSource.Bytes]] {
  import AeronSource._
  import TaskRunner._

  val out: Outlet[Bytes] = Outlet("AeronSource")
  override val shape: SourceShape[Bytes] = SourceShape(out)

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic =
    new GraphStageLogic(shape) with OutHandler {

      private val sub = aeron.addSubscription(channel, streamId)
      private val spinning = 1000
      private val yielding = 0
      private val parking = 0
      private val idleStrategy = new BackoffIdleStrategy(
        spinning, yielding, TimeUnit.MICROSECONDS.toNanos(1), TimeUnit.MICROSECONDS.toNanos(100))
      private val idleStrategyRetries = spinning + yielding + parking
      private var backoffCount = idleStrategyRetries

      // the fragmentHandler is called from `poll` in same thread, i.e. no async callback is needed
      private val messageHandler = new MessageHandler
      private val addPollTask: Add = Add(pollTask(sub, messageHandler, getAsyncCallback(onMessage)))

      override def postStop(): Unit = {
        sub.close()
        taskRunner.command(Remove(addPollTask.task))
      }

      // OutHandler
      override def onPull(): Unit = {
        idleStrategy.reset()
        backoffCount = idleStrategyRetries
        subscriberLoop()
      }

      @tailrec private def subscriberLoop(): Unit = {
        messageHandler.reset()
        val fragmentsRead = sub.poll(messageHandler.fragmentsHandler, 1)
        val msg = messageHandler.messageReceived
        messageHandler.reset() // for GC
        if (fragmentsRead > 0) {
          if (msg ne null)
            onMessage(msg)
          else
            subscriberLoop() // recursive, read more fragments
        } else {
          // TODO the backoff strategy should be measured and tuned
          backoffCount -= 1
          if (backoffCount > 0) {
            idleStrategy.idle()
            subscriberLoop() // recursive
          } else {
            // delegate backoff to shared TaskRunner
            taskRunner.command(addPollTask)
          }
        }
      }

      private def onMessage(data: Bytes): Unit = {
        push(out, data)
      }

      setHandler(out, this)
    }
}
