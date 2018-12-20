/*
 * Copyright (C) 2015-2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.stream.impl.io

import java.io.{ IOException, OutputStream }
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.Semaphore

import akka.stream.Attributes.InputBuffer
import akka.stream.impl.Stages.DefaultAttributes
import akka.stream.impl.io.OutputStreamSourceStage._
import akka.stream.stage._
import akka.stream.{ Attributes, Outlet, SourceShape }
import akka.util.ByteString

import scala.concurrent.duration.FiniteDuration
import scala.util.control.NonFatal

private[stream] object OutputStreamSourceStage {
  sealed trait AdapterToStageMessage
  case class Send(data: ByteString) extends AdapterToStageMessage
  case object Close extends AdapterToStageMessage

  sealed trait DownstreamStatus
  case object Ok extends DownstreamStatus
  case object Canceled extends DownstreamStatus

}

final private[stream] class OutputStreamSourceStage(writeTimeout: FiniteDuration) extends GraphStageWithMaterializedValue[SourceShape[ByteString], OutputStream] {
  val out = Outlet[ByteString]("OutputStreamSource.out")
  override def initialAttributes = DefaultAttributes.outputStreamSource
  override val shape: SourceShape[ByteString] = SourceShape.of(out)

  override def createLogicAndMaterializedValue(inheritedAttributes: Attributes): (GraphStageLogic, OutputStream) = {
    val maxBuffer = inheritedAttributes.get[InputBuffer](InputBuffer(16, 16)).max

    require(maxBuffer > 0, "Buffer size must be greater than 0")

    // Semaphore counting the number of elements we are ready to accept,
    // which is the demand plus the size of the buffer.
    val semaphore = new Semaphore(maxBuffer, /* fair =*/ true)

    val downstreamStatus = new AtomicReference[DownstreamStatus](Ok)

    final class OutputStreamSourceLogic extends GraphStageLogic(shape) {

      private val upstreamCallback: AsyncCallback[AdapterToStageMessage] =
        getAsyncCallback(onAsyncMessage)

      def wakeUp(msg: AdapterToStageMessage): Unit =
        upstreamCallback.invoke(msg)

      private def onAsyncMessage(event: AdapterToStageMessage): Unit = {
        event match {
          case Send(data) ⇒
            emit(out, data)
          case Close ⇒
            downstreamStatus.set(Canceled)
            completeStage()
        }
      }

      setHandler(out, new OutHandler {
        override def onPull(): Unit = {
          semaphore.release()
        }

        override def onDownstreamFinish(): Unit = {
          downstreamStatus.set(Canceled)
          super.onDownstreamFinish()
        }
      })

      override def postStop(): Unit = {
        downstreamStatus.set(Canceled)
        super.postStop()
      }
    }

    val logic = new OutputStreamSourceLogic
    (logic, new OutputStreamAdapter(semaphore, downstreamStatus, logic.wakeUp, writeTimeout))
  }
}

private[akka] class OutputStreamAdapter(
  unfulfilledDemand: Semaphore,
  downstreamStatus:  AtomicReference[DownstreamStatus],
  sendToStage:       (AdapterToStageMessage) ⇒ Unit,
  writeTimeout:      FiniteDuration)
  extends OutputStream {

  var isActive = true
  var isPublisherAlive = true
  def publisherClosedException = new IOException("Reactive stream is terminated, no writes are possible")

  @scala.throws(classOf[IOException])
  private[this] def send(sendAction: () ⇒ Unit): Unit = {
    if (isActive) {
      if (isPublisherAlive) sendAction()
      else throw publisherClosedException
    } else throw new IOException("OutputStream is closed")
  }

  @scala.throws(classOf[IOException])
  private[this] def sendData(data: ByteString): Unit =
    send(() ⇒ {
      try {
        // FIXME take into account writeTimeout here.
        unfulfilledDemand.acquire()
        sendToStage(Send(data))
      } catch { case NonFatal(ex) ⇒ throw new IOException(ex) }
      if (downstreamStatus.get() == Canceled) {
        isPublisherAlive = false
        throw publisherClosedException
      }
    })

  @scala.throws(classOf[IOException])
  override def write(b: Int): Unit = {
    sendData(ByteString(b))
  }

  @scala.throws(classOf[IOException])
  override def write(b: Array[Byte], off: Int, len: Int): Unit = {
    sendData(ByteString.fromArray(b, off, len))
  }

  @scala.throws(classOf[IOException])
  override def flush(): Unit =
    // Flushing does nothing: at best we could guarantee that our own buffer
    // is empty, but that doesn't mean the element has been accepted downstream,
    // so there is little value in that.
    ()

  @scala.throws(classOf[IOException])
  override def close(): Unit = {
    send(() ⇒
      try {
        sendToStage(Close)
      } catch {
        case e: IOException ⇒ throw e
        case NonFatal(e)    ⇒ throw new IOException(e)
      })
    isActive = false
  }
}
