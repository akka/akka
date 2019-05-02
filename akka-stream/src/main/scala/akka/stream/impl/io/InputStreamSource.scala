/*
 * Copyright (C) 2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.stream.impl.io

import java.io.InputStream

import akka.annotation.InternalApi
import akka.stream.impl.Stages.DefaultAttributes
import akka.stream.{
  AbruptStageTerminationException,
  Attributes,
  IOOperationIncompleteException,
  IOResult,
  Outlet,
  SourceShape
}
import akka.stream.stage.{ GraphStageLogic, GraphStageLogicWithLogging, GraphStageWithMaterializedValue, OutHandler }
import akka.util.ByteString

import scala.concurrent.{ Future, Promise }
import scala.util.control.NonFatal

case class DownstreamFinishedException()
    extends RuntimeException("Downstream Finished before stream was fully consumed")

/**
 * INTERNAL API
 */
@InternalApi
private[akka] final class InputStreamSource(factory: () => InputStream, chunkSize: Int)
    extends GraphStageWithMaterializedValue[SourceShape[ByteString], Future[IOResult]] {

  require(chunkSize > 0, s"chunkSize must be > 0 (was $chunkSize)")

  private val out: Outlet[ByteString] = Outlet("InputStreamSource.out")

  override def shape: SourceShape[ByteString] = SourceShape(out)

  override protected def initialAttributes: Attributes = DefaultAttributes.inputStreamSource

  override def createLogicAndMaterializedValue(inheritedAttributes: Attributes): (GraphStageLogic, Future[IOResult]) = {
    val mat = Promise[IOResult]
    val logic = new GraphStageLogicWithLogging(shape) with OutHandler {
      private val buffer = new Array[Byte](chunkSize)
      private var readBytesTotal = 0L
      private var inputStream: InputStream = _
      private var closed = false

      override def preStart(): Unit = {
        try {
          inputStream = factory()
        } catch {
          case NonFatal(t) =>
            mat.failure(IOOperationIncompleteException(0, t))
            failStage(t)
        }
      }

      override def onPull(): Unit = if (isAvailable(out)) {
        try {
          val readBytes = inputStream.read(buffer)
          readBytes match {
            case -1 =>
              closed = true
              closeStage()
            case _ =>
              readBytesTotal += readBytes
              push(out, ByteString.fromArray(buffer, 0, readBytes))
          }
        } catch {
          case NonFatal(t) =>
            failStream(t)
            failStage(t)
        }
      }

      override def onDownstreamFinish(): Unit = {
        if (!closed) {
          closeInputStream()
          mat.tryFailure(IOOperationIncompleteException(readBytesTotal, DownstreamFinishedException()))
        }
      }

      override def postStop(): Unit = {
        if (!closed) {
          mat.tryFailure(new AbruptStageTerminationException(this))
        }
      }

      private def closeStage(): Unit = {
        closeInputStream()
        mat.success(IOResult(readBytesTotal))
        completeStage()
      }

      private def failStream(reason: Throwable): Unit = {
        closeInputStream()
        mat.tryFailure(IOOperationIncompleteException(readBytesTotal, reason))
      }

      private def closeInputStream(): Unit = {
        try {
          if (inputStream != null)
            inputStream.close()
        } catch {
          case ex: Exception =>
            mat.tryFailure(IOOperationIncompleteException(readBytesTotal, ex))
            failStage(ex)
        }
      }

      setHandler(out, this)
    }
    (logic, mat.future)

  }
  override def toString: String = "InputStreamSource"
}
