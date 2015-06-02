/**
 * Copyright (C) 2015 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.impl.io

import java.io.OutputStream

import akka.actor.{ Deploy, ActorLogging, Props }
import akka.stream.actor.{ ActorSubscriberMessage, WatermarkRequestStrategy }
import akka.util.ByteString

import scala.concurrent.Promise

/** INTERNAL API */
private[akka] object OutputStreamSubscriber {
  def props(os: OutputStream, completionPromise: Promise[Long], bufSize: Int) = {
    require(bufSize > 0, "buffer size must be > 0")
    Props(classOf[OutputStreamSubscriber], os, completionPromise, bufSize).withDeploy(Deploy.local)
  }

}

/** INTERNAL API */
private[akka] class OutputStreamSubscriber(os: OutputStream, bytesWrittenPromise: Promise[Long], bufSize: Int)
  extends akka.stream.actor.ActorSubscriber
  with ActorLogging {

  override protected val requestStrategy = WatermarkRequestStrategy(highWatermark = bufSize)

  private var bytesWritten: Long = 0

  def receive = {
    case ActorSubscriberMessage.OnNext(bytes: ByteString) ⇒
      try {
        // blocking write
        os.write(bytes.toArray)
        bytesWritten += bytes.length
      } catch {
        case ex: Exception ⇒
          println("ex = " + ex)
          bytesWrittenPromise.failure(ex)
          cancel()
      }

    case ActorSubscriberMessage.OnError(cause) ⇒
      log.error(cause, "Tearing down OutputStreamSink due to upstream error, wrote bytes: {}", bytesWritten)
      context.stop(self)

    case ActorSubscriberMessage.OnComplete ⇒
      context.stop(self)
      os.flush()
  }

  override def postStop(): Unit = {
    bytesWrittenPromise.trySuccess(bytesWritten)

    if (os ne null) os.close()
    super.postStop()
  }
}
