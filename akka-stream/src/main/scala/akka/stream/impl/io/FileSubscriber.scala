/*
 * Copyright (C) 2015-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.stream.impl.io

import java.nio.channels.FileChannel
import java.nio.file.{ OpenOption, Path }

import akka.Done
import akka.actor.{ ActorLogging, Deploy, Props }
import akka.annotation.InternalApi
import akka.stream.{ AbruptIOTerminationException, IOResult }
import akka.stream.actor.{ ActorSubscriberMessage, WatermarkRequestStrategy }
import akka.util.ByteString
import com.github.ghik.silencer.silent

import akka.util.ccompat.JavaConverters._
import scala.concurrent.Promise
import scala.util.{ Failure, Success, Try }

/** INTERNAL API */
@InternalApi private[akka] object FileSubscriber {
  def props(
      f: Path,
      completionPromise: Promise[IOResult],
      bufSize: Int,
      startPosition: Long,
      openOptions: Set[OpenOption]) = {
    require(bufSize > 0, "buffer size must be > 0")
    require(startPosition >= 0, s"startPosition must be >= 0 (was $startPosition)")
    Props(classOf[FileSubscriber], f, completionPromise, bufSize, startPosition, openOptions).withDeploy(Deploy.local)
  }
}

/** INTERNAL API */
@silent
@InternalApi private[akka] class FileSubscriber(
    f: Path,
    completionPromise: Promise[IOResult],
    bufSize: Int,
    startPosition: Long,
    openOptions: Set[OpenOption])
    extends akka.stream.actor.ActorSubscriber
    with ActorLogging {

  override protected val requestStrategy = WatermarkRequestStrategy(highWatermark = bufSize)

  private var chan: FileChannel = _

  private var bytesWritten: Long = 0

  override def preStart(): Unit =
    try {
      chan = FileChannel.open(f, openOptions.asJava)
      if (startPosition > 0) {
        chan.position(startPosition)
      }

      super.preStart()
    } catch {
      case ex: Exception =>
        closeAndComplete(Failure(ex))
        cancel()
    }

  def receive = {
    case ActorSubscriberMessage.OnNext(bytes: ByteString) =>
      try {
        bytesWritten += chan.write(bytes.asByteBuffer)
      } catch {
        case ex: Exception =>
          closeAndComplete(Success(IOResult(bytesWritten, Failure(ex))))
          cancel()
      }

    case ActorSubscriberMessage.OnError(ex) =>
      log.error(ex, "Tearing down FileSink({}) due to upstream error", f)
      closeAndComplete(Failure(AbruptIOTerminationException(IOResult(bytesWritten, Success(Done)), ex)))
      context.stop(self)

    case ActorSubscriberMessage.OnComplete => context.stop(self)
  }

  override def postStop(): Unit = {
    closeAndComplete(Success(IOResult(bytesWritten, Success(Done))))
    super.postStop()
  }

  private def closeAndComplete(result: Try[IOResult]): Unit = {
    try {
      // close the channel/file before completing the promise, allowing the
      // file to be deleted, which would not work (on some systems) if the
      // file is still open for writing
      if (chan ne null) chan.close()
      completionPromise.tryComplete(result)
    } catch {
      case closingException: Exception =>
        result match {
          case Success(ioResult) =>
            val statusWithClosingException =
              ioResult.status.transform(_ => Failure(closingException), ex => Failure(closingException.initCause(ex)))
            completionPromise.trySuccess(ioResult.copy(status = statusWithClosingException))
          case Failure(ex) => completionPromise.tryFailure(closingException.initCause(ex))
        }
    }
  }
}
