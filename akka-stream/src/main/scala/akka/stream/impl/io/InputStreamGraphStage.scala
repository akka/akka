/*
 * Copyright (C) 2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.stream.impl.io

import java.io.InputStream

import akka.Done
import akka.stream.impl.Stages.DefaultAttributes
import akka.stream.{ Attributes, IOResult, Outlet, SourceShape }
import akka.stream.stage.{ GraphStageLogic, GraphStageLogicWithLogging, GraphStageWithMaterializedValue, OutHandler }
import akka.util.ByteString

import scala.concurrent.{ Future, Promise }
import scala.util.{ Failure, Success }
import scala.util.control.NonFatal

/**
 * INTERNAL API
 */
private[akka] class InputStreamGraphStage(factory: () => InputStream, chunkSize: Int)
    extends GraphStageWithMaterializedValue[SourceShape[ByteString], Future[IOResult]] {

  val out: Outlet[ByteString] = Outlet("InputStreamSource")

  /**
   * The shape of a graph is all that is externally visible: its inlets and outlets.
   */
  override def shape: SourceShape[ByteString] = SourceShape(out)

  override protected def initialAttributes: Attributes = DefaultAttributes.inputStreamSource

  override def createLogicAndMaterializedValue(inheritedAttributes: Attributes): (GraphStageLogic, Future[IOResult]) = {
    val mat = Promise[IOResult]
    val logic = new GraphStageLogicWithLogging(shape) with OutHandler {
      val buffer = new Array[Byte](chunkSize)
      var readBytesTotal = 0L
      val inputStream = factory()

      override def onPull(): Unit = {
        tryRead()
      }

      def tryRead(): Unit = {
        if (isAvailable(out)) {
          try {
            val readBytes = inputStream.read(buffer)
            readBytes match {
              case -1 =>
                completeStage()
              case _ =>
                readBytesTotal += readBytes
                push(out, ByteString.fromArray(buffer, 0, readBytes))
            }
            tryRead()
          } catch {
            case NonFatal(t) => failStage(t)
          }
        }
      }

      setHandler(out, this)

      override def postStop(): Unit = {
        try {
          if (inputStream != null)
            inputStream.close()

          mat.trySuccess(IOResult(readBytesTotal, Success(Done)))
        } catch {
          case ex: Exception =>
            mat.success(IOResult(readBytesTotal, Failure(ex)))
        }
      }
    }
    (logic, mat.future)
  }
}
