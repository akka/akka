/*
 * Copyright (C) 2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.aeron.scaladsl

import akka.NotUsed
import akka.actor.ActorSystem
import akka.aeron.internal.PollTask.MessageHandler
import akka.aeron.internal.{ PollTask, TaskRunner }
import akka.aeron.{ AeronExtension, TaskRunnerExtension }
import akka.stream.scaladsl.Source
import akka.stream.{ Attributes, Outlet, SourceShape }
import akka.stream.stage._
import akka.util.ByteString
import io.aeron.exceptions.DriverTimeoutException
import io.aeron.logbuffer.{ FragmentHandler, Header }
import io.aeron.{ Aeron, FragmentAssembler }
import org.agrona.DirectBuffer
import org.agrona.hints.ThreadHints

import scala.annotation.tailrec

object AeronSource {

  class ByteStringMessageHandler extends MessageHandler[ByteString] {
    override val fragmentsHandler: FragmentHandler = new FragmentAssembler(new FragmentHandler {
      override def onFragment(buffer: DirectBuffer, offset: Int, length: Int, header: Header): Unit = {
        // FIXME, can we use the underlying array or byte buffer to avoid this? there will be at least one copy as we want an immutable data structure
        // FIXME, if not then use a builder to avoid the additional array
        val dst = new Array[Byte](length)
        buffer.getBytes(offset, dst)
        messageReceived = ByteString(dst)
      }
    })
  }

  // TODO should this be tied to an actor system?
  def apply(system: ActorSystem, settings: AeronSourceSettings): Source[ByteString, NotUsed] = {
    Source.fromGraph(new AeronSource(AeronExtension(system).aeron, settings, TaskRunnerExtension(system).taskRunner))
  }

}

// TODO not a case class
case class AeronSourceSettings(channel: String, streamId: Int, spinning: Int)

private class AeronSource private[akka] (aeron: Aeron, settings: AeronSourceSettings, taskRunner: TaskRunner)
  extends GraphStage[SourceShape[ByteString]] {

  import akka.aeron.internal.TaskRunner._
  import AeronSource._

  private val out: Outlet[ByteString] = Outlet("AeronSource")
  override val shape: SourceShape[ByteString] = SourceShape(out)

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic = {
    val logic = new GraphStageLogicWithLogging(shape) with OutHandler {

      private val subscription = aeron.addSubscription(settings.channel, settings.streamId)
      private var backoffCount = settings.spinning

      // the fragmentHandler is called from `poll` in same thread, i.e. no async callback is needed
      private val messageHandler = new ByteStringMessageHandler()
      private val addPollTask: Add = Add(PollTask.pollTask[ByteString](subscription, messageHandler, getAsyncCallback(onMessage)))

      override def postStop(): Unit = {
        taskRunner.command(Remove(addPollTask.task))
        try subscription.close() catch {
          case e: DriverTimeoutException ⇒
            // media driver was shutdown
            log.debug("DriverTimeout when closing subscription. {}", e)
        }
      }

      override def onPull(): Unit = {
        backoffCount = settings.spinning
        subscriberLoop()
      }

      @tailrec private def subscriberLoop(): Unit = {
        messageHandler.reset()
        val fragmentsRead = subscription.poll(messageHandler.fragmentsHandler, 1)
        val msg = messageHandler.messageReceived
        messageHandler.reset() // for GC
        if (fragmentsRead > 0) {
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
            taskRunner.command(addPollTask)
          }
        }
      }

      private def onMessage(data: ByteString): Unit = {
        push(out, data)
      }

      setHandler(out, this)
    }

    logic
  }

}
