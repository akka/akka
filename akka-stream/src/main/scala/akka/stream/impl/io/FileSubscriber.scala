/**
 * Copyright (C) 2015 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.impl.io

import java.io.File
import java.nio.channels.FileChannel
import java.util.Collections

import akka.actor.{ Deploy, ActorLogging, Props }
import akka.stream.actor.{ ActorSubscriberMessage, WatermarkRequestStrategy }
import akka.util.ByteString

import scala.concurrent.Promise

/** INTERNAL API */
private[akka] object FileSubscriber {
  def props(f: File, completionPromise: Promise[Long], bufSize: Int, append: Boolean) = {
    require(bufSize > 0, "buffer size must be > 0")
    Props(classOf[FileSubscriber], f, completionPromise, bufSize, append).withDeploy(Deploy.local)
  }

  import java.nio.file.StandardOpenOption._
  val Write = Collections.singleton(WRITE)
  val Append = Collections.singleton(APPEND)
}

/** INTERNAL API */
private[akka] class FileSubscriber(f: File, bytesWrittenPromise: Promise[Long], bufSize: Int, append: Boolean)
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
      bytesWrittenPromise.failure(ex)
      cancel()
  }

  def receive = {
    case ActorSubscriberMessage.OnNext(bytes: ByteString) ⇒
      try {
        bytesWritten += chan.write(bytes.asByteBuffer)
      } catch {
        case ex: Exception ⇒
          bytesWrittenPromise.failure(ex)
          cancel()
      }

    case ActorSubscriberMessage.OnError(cause) ⇒
      log.error(cause, "Tearing down FileSink({}) due to upstream error", f.getAbsolutePath)
      context.stop(self)

    case ActorSubscriberMessage.OnComplete ⇒
      try {
        chan.force(true)
      } catch {
        case ex: Exception ⇒
          bytesWrittenPromise.failure(ex)
      }
      context.stop(self)
  }

  override def postStop(): Unit = {
    bytesWrittenPromise.trySuccess(bytesWritten)

    if (chan ne null) chan.close()
    super.postStop()
  }
}
