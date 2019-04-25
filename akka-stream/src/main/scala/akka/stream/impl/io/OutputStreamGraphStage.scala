package akka.stream.impl.io

import java.io.OutputStream

import akka.Done
import akka.stream.impl.Stages.DefaultAttributes
import akka.stream.{ AbruptIOTerminationException, Attributes, IOResult, Inlet, Materializer, SinkShape }
import akka.stream.stage.{ GraphStageLogic, GraphStageLogicWithLogging, GraphStageWithMaterializedValue, InHandler }
import akka.util.ByteString

import scala.concurrent.{ Future, Promise }
import scala.util.{ Failure, Success }
import scala.util.control.NonFatal

class OutputStreamGraphStage(factory: () => OutputStream, autoFlush: Boolean)
    extends GraphStageWithMaterializedValue[SinkShape[ByteString], Future[IOResult]] {
  override def createLogicAndMaterializedValue(inheritedAttributes: Attributes): (GraphStageLogic, Nothing) = ???

  val in = Inlet[ByteString]("OutputStreamSink")

  override def shape: SinkShape[ByteString] = SinkShape(in)

  override protected def initialAttributes: Attributes = DefaultAttributes.outputStreamSink

  override def createLogicAndMaterializedValue(
      inheritedAttributes: Attributes,
      materializer: Materializer): (GraphStageLogic, Future[IOResult]) = {
    val mat = Promise[IOResult]
    val logic = new GraphStageLogicWithLogging(shape) with InHandler {
      var outputStream: OutputStream = _
      val maxInputBufferSize = attributes.mandatoryAttribute[Attributes.InputBuffer].max
      var bytesWritten = 0
      override def preStart(): Unit = {
        try {
          outputStream = factory()
        } catch {
          case NonFatal(t) =>
          // FIXME
        }
        pull(in)
      }

      override def onPush(): Unit = {
        val next = grab(in)
        try {
          outputStream.write(next.toArray)
          if (autoFlush) outputStream.flush()
        } catch {
          case NonFatal(t) =>
            mat.success(IOResult(bytesWritten, Failure(t)))
            failStage(t)
        }
      }

      override def onUpstreamFailure(ex: Throwable): Unit = {
        mat.failure(AbruptIOTerminationException(IOResult(bytesWritten, Success(Done)), ex))
      }

      override def onUpstreamFinish(): Unit = {
        outputStream.flush()
      }

      override def postStop(): Unit = {
        try {
          if (outputStream != null) outputStream.close()
          mat.trySuccess(IOResult(bytesWritten, Success(Done)))
        } catch {
          case NonFatal(t) =>
            mat.success(IOResult(bytesWritten, Failure(t)))
        }
      }

      setHandler(in, this)
    }

    (logic, mat.future)

  }

}
