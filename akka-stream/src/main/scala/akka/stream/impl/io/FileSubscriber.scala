/**
 * Copyright (C) 2015-2016 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.impl.io

import java.io.File
import java.nio.channels.FileChannel
import java.util.Collections

import akka.Done
import akka.actor.{ Deploy, ActorLogging, Props }
import akka.stream.io.IOResult
import akka.stream.actor.{ ActorSubscriberMessage, WatermarkRequestStrategy }
import akka.util.ByteString

import scala.concurrent.Promise
import scala.util.{ Failure, Success }

/** INTERNAL API */
private[akka] object FileSubscriber {
  def props(f: File, completionPromise: Promise[IOResult], bufSize: Int, append: Boolean) = {
    require(bufSize > 0, "buffer size must be > 0")
    Props(classOf[FileSubscriber], f, completionPromise, bufSize, append).withDeploy(Deploy.local)
  }

  import java.nio.file.StandardOpenOption._
  val Write = Collections.singleton(WRITE)
  val Append = Collections.singleton(APPEND)
}

/** INTERNAL API */
private[akka] class FileSubscriber(f: File, completionPromise: Promise[IOResult], bufSize: Int, append: Boolean)
  extends akka.stream.actor.ActorSubscriber
  with ActorLogging {

  override protected val requestStrategy = WatermarkRequestStrategy(highWatermark = bufSize)

  private var chan: FileChannel = _

  private var bytesWritten: Long = 0

  override def preStart(): Unit = try {
    val openOptions = if (append) FileSubscriber.Append else FileSubscriber.Write
    chan = FileChannel.open(f.toPath, openOptions)

    super.preStart()
  } catch {
    case ex: Exception ⇒
      completionPromise.success(IOResult(bytesWritten, Failure(ex)))
      cancel()
  }

  def receive = {
    case ActorSubscriberMessage.OnNext(bytes: ByteString) ⇒
      try {
        bytesWritten += chan.write(bytes.asByteBuffer)
      } catch {
        case ex: Exception ⇒
          completionPromise.success(IOResult(bytesWritten, Failure(ex)))
          cancel()
      }

    case ActorSubscriberMessage.OnError(ex) ⇒
      log.error(ex, "Tearing down FileSink({}) due to upstream error", f.getAbsolutePath)
      completionPromise.success(IOResult(bytesWritten, Failure(ex)))
      context.stop(self)

    case ActorSubscriberMessage.OnComplete ⇒
      try {
        chan.force(true)
      } catch {
        case ex: Exception ⇒
          completionPromise.success(IOResult(bytesWritten, Failure(ex)))
      }
      context.stop(self)
  }

  override def postStop(): Unit = {
    try {
      if (chan ne null) chan.close()
    } catch {
      case ex: Exception ⇒
        completionPromise.success(IOResult(bytesWritten, Failure(ex)))
    }

    completionPromise.trySuccess(IOResult(bytesWritten, Success(Done)))
    super.postStop()
  }
}
