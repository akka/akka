/*
 * Copyright (C) 2015-2018 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.stream.impl.io

import java.io.{ IOException, OutputStream }
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.{ Semaphore, TimeUnit }

import akka.dispatch.ExecutionContexts
import akka.stream.Attributes.InputBuffer
import akka.stream.impl.Stages.DefaultAttributes
import akka.stream.impl.io.OutputStreamSourceStage._
import akka.stream.stage._
import akka.stream.{ Attributes, Outlet, SourceShape }
import akka.util.ByteString

import scala.concurrent.Await
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

    final class OutputStreamSourceLogic extends GraphStageLogic(shape) {

      val upstreamCallback: AsyncCallback[AdapterToStageMessage] =
        getAsyncCallback(onAsyncMessage)

      private def onAsyncMessage(event: AdapterToStageMessage): Unit = {
        event match {
          case Send(data) ⇒
            emit(out, data)
          case Close ⇒
            completeStage()
        }
      }

      setHandler(out, new OutHandler {
        override def onPull(): Unit = {
          semaphore.release()
        }
      })
    }

    val logic = new OutputStreamSourceLogic
    (logic, new OutputStreamAdapter(semaphore, logic.upstreamCallback, writeTimeout))
  }
}

private[akka] class OutputStreamAdapter(
  unfulfilledDemand: Semaphore,
  sendToStage:       AsyncCallback[AdapterToStageMessage],
  writeTimeout:      FiniteDuration)
  extends OutputStream {

  @scala.throws(classOf[IOException])
  private[this] def sendData(data: ByteString): Unit = {
    if (!unfulfilledDemand.tryAcquire(writeTimeout.toMillis, TimeUnit.MILLISECONDS)) {
      throw new IOException("Timed out trying to write data to stream")
    }

    val invocationResult =
      sendToStage.invokeWithFeedback(Send(data))
        .recoverWith {
          case NonFatal(e) ⇒ throw new IOException(e)
        }(ExecutionContexts.sameThreadExecutionContext)

    Await.result(invocationResult, writeTimeout)
  }

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
    val invocationResult = sendToStage.invokeWithFeedback(Close)
      .recoverWith { case NonFatal(e) ⇒ throw new IOException(e) }(ExecutionContexts.sameThreadExecutionContext)
    Await.result(invocationResult, writeTimeout)
  }
}
