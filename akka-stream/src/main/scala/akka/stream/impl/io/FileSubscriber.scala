/**
 * Copyright (C) 2015-2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.stream.impl.io

import java.io.File
import java.nio.channels.FileChannel
import java.nio.file.StandardOpenOption

import akka.Done
import akka.actor.{ Deploy, ActorLogging, Props }
import akka.stream.IOResult
import akka.stream.actor.{ ActorSubscriberMessage, WatermarkRequestStrategy }
import akka.util.ByteString

import scala.collection.JavaConverters._
import scala.concurrent.Promise
import scala.util.{ Failure, Success }

/** INTERNAL API */
private[akka] object FileSubscriber {
  def props(f: File, completionPromise: Promise[IOResult], bufSize: Int, openOptions: Set[StandardOpenOption]) = {
    require(bufSize > 0, "buffer size must be > 0")
    Props(classOf[FileSubscriber], f, completionPromise, bufSize, openOptions).withDeploy(Deploy.local)
  }
}

/** INTERNAL API */
private[akka] class FileSubscriber(f: File, completionPromise: Promise[IOResult], bufSize: Int, openOptions: Set[StandardOpenOption])
  extends akka.stream.actor.ActorSubscriber
  with ActorLogging {

  override protected val requestStrategy = WatermarkRequestStrategy(highWatermark = bufSize)

  private var chan: FileChannel = _

  private var bytesWritten: Long = 0

  override def preStart(): Unit = try {
    chan = FileChannel.open(f.toPath, openOptions.asJava)

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
      log.error(ex, "Tearing down FileSink({}) due to upstream error", f.getAbsolutePath)
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
