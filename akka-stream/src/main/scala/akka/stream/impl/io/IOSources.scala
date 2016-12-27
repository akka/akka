/**
 * Copyright (C) 2015-2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.stream.impl.io

import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.channels.{ AsynchronousFileChannel, CompletionHandler }
import java.nio.file.{ Files, Path, StandardOpenOption }

import akka.Done
import akka.stream.Attributes.InputBuffer
import akka.stream._
import akka.stream.ActorAttributes.Dispatcher
import akka.stream.IOResult
import akka.stream.impl.Stages.DefaultAttributes
import akka.stream.impl.StreamLayout.Module
import akka.stream.impl.Stages.DefaultAttributes.IODispatcher
import akka.stream.impl.{ ErrorPublisher, SourceModule }
import akka.stream.stage._
import akka.util.ByteString
import org.reactivestreams._

import scala.concurrent.{ Future, Promise }
import scala.util.control.NonFatal
import scala.util.{ Failure, Success, Try }

/**
 * INTERNAL API
 */
private[akka] object FileSource {

  val completionHandler = new CompletionHandler[Integer, Try[Int] ⇒ Unit] {

    override def completed(result: Integer, attachment: Try[Int] ⇒ Unit): Unit = {
      attachment(Success(result))
    }

    override def failed(ex: Throwable, attachment: Try[Int] ⇒ Unit): Unit = {
      attachment(Failure(ex))
    }
  }
}

/**
 * INTERNAL API
 * Creates simple asynchronous Source backed by the given file.
 */
private[akka] final class FileSource(path: Path, chunkSize: Int)
  extends GraphStageWithMaterializedValue[SourceShape[ByteString], Future[IOResult]] {
  require(chunkSize > 0, "chunkSize must be greater than 0")

  val out = Outlet[ByteString]("FileSource.out")

  override protected def initialAttributes: Attributes = DefaultAttributes.fileSource

  override val shape = SourceShape(out)

  override def createLogicAndMaterializedValue(inheritedAttributes: Attributes): (GraphStageLogic, Future[IOResult]) = {
    val ioResultPromise = Promise[IOResult]()

    val logic = new TimerGraphStageLogic(shape) with OutHandler {
      val maxReadAhead = inheritedAttributes.getAttribute(classOf[InputBuffer], InputBuffer(16, 16)).max

      val bufferSize = if (maxReadAhead > 2) (chunkSize * maxReadAhead) / 2 else chunkSize
      val buffer = ByteBuffer.allocate(bufferSize)

      var channel: AsynchronousFileChannel = _
      var position = 0L
      var chunkCallback: Try[Int] ⇒ Unit = _
      var readBytes = -1
      var waitingForData = false

      private def dataIsReady = readBytes != -1
      private def setDataReady(bytes: Int) = readBytes = bytes

      override def preStart(): Unit = {
        try {
          // this is a bit weird but required to keep existing semantics
          require(Files.exists(path), s"Path '$path' does not exist")
          require(Files.isRegularFile(path), s"Path '$path' is not a regular file")
          require(Files.isReadable(path), s"Missing read permission for '$path'")

          channel = AsynchronousFileChannel.open(path, StandardOpenOption.READ)
        } catch {
          case NonFatal(ex) ⇒
            ioResultPromise.trySuccess(IOResult(position, Failure(ex)))
            throw ex
        }

        chunkCallback = getAsyncCallback[Try[Int]] {
          case Success(readBytes) ⇒
            waitingForData = false
            setDataReady(readBytes)
            if (isAvailable(out)) pushBuffer()

          case Failure(ex) ⇒
            failStage(ex)
            ioResultPromise.trySuccess(IOResult(position, Failure(ex)))

        }.invoke
      }

      private def pushBuffer(): Unit = {
        if (readBytes > 0) {
          buffer.flip()
          val up = math.ceil(readBytes.toDouble / chunkSize).toInt
          if (up == 1) {
            push(out, ByteString(buffer))
            if (readBytes < bufferSize) complete()
          } else {
            val list = for (i ← 1 to up) yield {
              val length = if (buffer.remaining > chunkSize) chunkSize else buffer.remaining
              val offset = (i - 1) * chunkSize
              buffer.position(offset + length)
              ByteString.fromArray(buffer.array(), offset, length)
            }

            if (readBytes == bufferSize) emitMultiple(out, list)
            else emitMultiple(out, list, () ⇒ complete())
          }
          position += readBytes
          buffer.clear()
        } else {
          // hit end, our work here is done
          complete()
        }
        if (readBytes == bufferSize) {
          setDataReady(-1)
          read() //pre fetch
        }
      }

      private def complete(): Unit = {
        completeStage()
        ioResultPromise.trySuccess(IOResult(position, Success(Done)))
      }

      private def read(): Unit = if (!waitingForData) {
        try {
          channel.read(buffer, position, chunkCallback, FileSource.completionHandler)
          waitingForData = true
        } catch {
          case NonFatal(ex) ⇒
            ioResultPromise.trySuccess(IOResult(position, Failure(ex)))
            throw ex
        }
      }

      setHandler(out, this)

      override def onPull(): Unit = if (dataIsReady) pushBuffer() else read()

      override def postStop(): Unit = {
        if ((channel ne null) && channel.isOpen) channel.close()
      }
    }

    (logic, ioResultPromise.future)
  }

  override def toString = s"FileSource($path, $chunkSize)"
}

/**
 * INTERNAL API
 * Source backed by the given input stream.
 */
private[akka] final class InputStreamSource(createInputStream: () ⇒ InputStream, chunkSize: Int, val attributes: Attributes, shape: SourceShape[ByteString])
  extends SourceModule[ByteString, Future[IOResult]](shape) {
  override def create(context: MaterializationContext) = {
    val materializer = ActorMaterializerHelper.downcast(context.materializer)
    val ioResultPromise = Promise[IOResult]()

    val pub = try {
      val is = createInputStream() // can throw, i.e. FileNotFound

      val props = InputStreamPublisher.props(is, ioResultPromise, chunkSize)

      val ref = materializer.actorOf(context, props)
      akka.stream.actor.ActorPublisher[ByteString](ref)
    } catch {
      case ex: Exception ⇒
        ioResultPromise.failure(ex)
        ErrorPublisher(ex, attributes.nameOrDefault("inputStreamSource")).asInstanceOf[Publisher[ByteString]]
    }

    (pub, ioResultPromise.future)
  }

  override protected def newInstance(shape: SourceShape[ByteString]): SourceModule[ByteString, Future[IOResult]] =
    new InputStreamSource(createInputStream, chunkSize, attributes, shape)

  override def withAttributes(attr: Attributes): Module =
    new InputStreamSource(createInputStream, chunkSize, attr, amendShape(attr))
}