/**
 * Copyright (C) 2015-2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.stream.impl.io

import java.nio.channels.FileChannel
import java.nio.file.{ Path, OpenOption }

import akka.Done
import akka.actor.{ ActorLogging, Deploy, Props }
import akka.annotation.InternalApi
import akka.stream.IOResult
import akka.stream.actor.{ ActorSubscriberMessage, WatermarkRequestStrategy }
import akka.util.ByteString

import scala.collection.JavaConverters._
import scala.concurrent.Promise
import scala.util.{ Failure, Success }

/** INTERNAL API */
@InternalApi private[akka] object FileSubscriber {
  def props(f: Path, completionPromise: Promise[IOResult], bufSize: Int, startPosition: Long, openOptions: Set[OpenOption]) = {
    require(bufSize > 0, "buffer size must be > 0")
    require(startPosition >= 0, s"startPosition must be >= 0 (was $startPosition)")
    Props(classOf[FileSubscriber], f, completionPromise, bufSize, startPosition, openOptions).withDeploy(Deploy.local)
  }
}

/** INTERNAL API */
@InternalApi private[akka] class FileSubscriber(f: Path, completionPromise: Promise[IOResult], bufSize: Int, startPosition: Long, openOptions: Set[OpenOption])
  extends akka.stream.actor.ActorSubscriber
  with ActorLogging {

  override protected val requestStrategy = WatermarkRequestStrategy(highWatermark = bufSize)

  private var chan: FileChannel = _

  private var bytesWritten: Long = 0

  override def preStart(): Unit = try {
    chan = FileChannel.open(f, openOptions.asJava)
    if (startPosition > 0) {
      chan.position(startPosition)
    }

    super.preStart()
  } catch {
    case ex: Exception ⇒
      closeAndComplete(IOResult(bytesWritten, Failure(ex)))
      cancel()
  }

  def receive = {
    case ActorSubscriberMessage.OnNext(bytes: ByteString) ⇒
      try {
        bytesWritten += chan.write(bytes.asByteBuffer)
      } catch {
        case ex: Exception ⇒
          closeAndComplete(IOResult(bytesWritten, Failure(ex)))
          cancel()
      }

    case ActorSubscriberMessage.OnError(ex) ⇒
      log.error(ex, "Tearing down FileSink({}) due to upstream error", f)
      closeAndComplete(IOResult(bytesWritten, Failure(ex)))
      context.stop(self)

    case ActorSubscriberMessage.OnComplete ⇒
      try {
        chan.force(true)
      } catch {
        case ex: Exception ⇒
          closeAndComplete(IOResult(bytesWritten, Failure(ex)))
      }
      context.stop(self)
  }

  override def postStop(): Unit = {
    closeAndComplete(IOResult(bytesWritten, Success(Done)))
    super.postStop()
  }

  private def closeAndComplete(result: IOResult): Unit = {
    try {
      // close the channel/file before completing the promise, allowing the
      // file to be deleted, which would not work (on some systems) if the
      // file is still open for writing
      if (chan ne null) chan.close()
      completionPromise.trySuccess(result)
    } catch {
      case ex: Exception ⇒
        completionPromise.trySuccess(IOResult(bytesWritten, Failure(ex)))
    }
  }
}
