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
import org.agrona.hints.ThreadHints

object AeronSource {

  private def pollTask(sub: Subscription, handler: MessageHandler, onMessage: AsyncCallback[EnvelopeBuffer]): () ⇒ Boolean = {
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

  class MessageHandler(pool: EnvelopeBufferPool) {
    def reset(): Unit = messageReceived = null

    private[remote] var messageReceived: EnvelopeBuffer = null // private to avoid scalac warning about exposing EnvelopeBuffer

    val fragmentsHandler = new Fragments(data ⇒ messageReceived = data, pool)
  }

  class Fragments(onMessage: EnvelopeBuffer ⇒ Unit, pool: EnvelopeBufferPool) extends FragmentAssembler(new FragmentHandler {
    override def onFragment(aeronBuffer: DirectBuffer, offset: Int, length: Int, header: Header): Unit = {
      val envelope = pool.acquire()
      aeronBuffer.getBytes(offset, envelope.byteBuffer, length)
      envelope.byteBuffer.flip()
      onMessage(envelope)
    }
  })
}

/**
 * @param channel eg. "aeron:udp?endpoint=localhost:40123"
 */
class AeronSource(
  channel:        String,
  streamId:       Int,
  aeron:          Aeron,
  taskRunner:     TaskRunner,
  pool:           EnvelopeBufferPool,
  flightRecorder: EventSink)
  extends GraphStage[SourceShape[EnvelopeBuffer]] {
  import AeronSource._
  import TaskRunner._
  import FlightRecorderEvents._

  val out: Outlet[EnvelopeBuffer] = Outlet("AeronSource")
  override val shape: SourceShape[EnvelopeBuffer] = SourceShape(out)

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic =
    new GraphStageLogic(shape) with OutHandler {

      private val sub = aeron.addSubscription(channel, streamId)
      // spin between 100 to 10000 depending on idleCpuLevel
      private val spinning = 1100 * taskRunner.idleCpuLevel - 1000
      private var backoffCount = spinning
      private var delegateTaskStartTime = 0L
      private var countBeforeDelegate = 0L

      // the fragmentHandler is called from `poll` in same thread, i.e. no async callback is needed
      private val messageHandler = new MessageHandler(pool)
      private val addPollTask: Add = Add(pollTask(sub, messageHandler, getAsyncCallback(taskOnMessage)))

      private val channelMetadata = channel.getBytes("US-ASCII")

      override def preStart(): Unit = {
        flightRecorder.loFreq(AeronSource_Started, channelMetadata)
      }

      override def postStop(): Unit = {
        sub.close()
        taskRunner.command(Remove(addPollTask.task))
        flightRecorder.loFreq(AeronSource_Stopped, channelMetadata)
      }

      // OutHandler
      override def onPull(): Unit = {
        backoffCount = spinning
        subscriberLoop()
      }

      @tailrec private def subscriberLoop(): Unit = {
        messageHandler.reset()
        val fragmentsRead = sub.poll(messageHandler.fragmentsHandler, 1)
        val msg = messageHandler.messageReceived
        messageHandler.reset() // for GC
        if (fragmentsRead > 0) {
          countBeforeDelegate += 1
          if (msg ne null)
            onMessage(msg)
          else
            subscriberLoop() // recursive, read more fragments
        } else {
          backoffCount -= 1
          if (backoffCount > 0) {
            ThreadHints.onSpinWait()
            subscriberLoop() // recursive
          } else {
            // delegate backoff to shared TaskRunner
            flightRecorder.hiFreq(AeronSource_DelegateToTaskRunner, countBeforeDelegate)
            delegateTaskStartTime = System.nanoTime()
            taskRunner.command(addPollTask)
          }
        }
      }

      private def taskOnMessage(data: EnvelopeBuffer): Unit = {
        countBeforeDelegate = 0
        flightRecorder.hiFreq(AeronSource_ReturnFromTaskRunner, System.nanoTime() - delegateTaskStartTime)
        onMessage(data)
      }

      private def onMessage(data: EnvelopeBuffer): Unit = {
        flightRecorder.hiFreq(AeronSource_Received, data.byteBuffer.limit)
        push(out, data)
      }

      setHandler(out, this)
    }
}
