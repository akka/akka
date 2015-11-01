/**
 * Copyright (C) 2015 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.impl.io

import java.io.{ File, IOException, InputStream, OutputStream }
import java.lang.{ Long ⇒ JLong }
import java.util.concurrent.{ LinkedBlockingQueue, BlockingQueue }

import akka.actor.{ ActorRef, Deploy }
import akka.japi
import akka.stream._
import akka.stream.ActorAttributes.Dispatcher
import akka.stream.impl.StreamLayout.Module
import akka.stream.impl.{ ErrorPublisher, SourceModule }
import akka.stream.scaladsl.{ Source, FlowGraph }
import akka.util.{ ByteString, Timeout }
import org.reactivestreams._

import scala.concurrent.duration.FiniteDuration
import scala.concurrent.{ Await, Future, Promise }
import scala.util.control.NonFatal

/**
 * INTERNAL API
 * Creates simple synchronous (Java 6 compatible) Source backed by the given file.
 */
private[akka] final class SynchronousFileSource(f: File, chunkSize: Int, val attributes: Attributes, shape: SourceShape[ByteString])
  extends SourceModule[ByteString, Future[Long]](shape) {
  override def create(context: MaterializationContext) = {
    // FIXME rewrite to be based on GraphStage rather than dangerous downcasts
    val mat = ActorMaterializer.downcast(context.materializer)
    val settings = mat.effectiveSettings(context.effectiveAttributes)

    val bytesReadPromise = Promise[Long]()
    val props = SynchronousFilePublisher.props(f, bytesReadPromise, chunkSize, settings.initialInputBufferSize, settings.maxInputBufferSize)
    val dispatcher = context.effectiveAttributes.get[Dispatcher](IOSettings.IODispatcher).dispatcher

    val ref = mat.actorOf(context, props.withDispatcher(dispatcher))

    (akka.stream.actor.ActorPublisher[ByteString](ref), bytesReadPromise.future)
  }

  override protected def newInstance(shape: SourceShape[ByteString]): SourceModule[ByteString, Future[Long]] =
    new SynchronousFileSource(f, chunkSize, attributes, shape)

  override def withAttributes(attr: Attributes): Module =
    new SynchronousFileSource(f, chunkSize, attr, amendShape(attr))
}

/**
 * INTERNAL API
 * Source backed by the given input stream.
 */
private[akka] final class InputStreamSource(createInputStream: () ⇒ InputStream, chunkSize: Int, val attributes: Attributes, shape: SourceShape[ByteString])
  extends SourceModule[ByteString, Future[Long]](shape) {
  override def create(context: MaterializationContext) = {
    val mat = ActorMaterializer.downcast(context.materializer)
    val settings = mat.effectiveSettings(context.effectiveAttributes)
    val bytesReadPromise = Promise[Long]()

    val pub = try {
      val is = createInputStream() // can throw, i.e. FileNotFound

      val props = InputStreamPublisher.props(is, bytesReadPromise, chunkSize, settings.initialInputBufferSize, settings.maxInputBufferSize)

      val ref = mat.actorOf(context, props)
      akka.stream.actor.ActorPublisher[ByteString](ref)
    } catch {
      case ex: Exception ⇒
        bytesReadPromise.failure(ex)
        ErrorPublisher(ex, attributes.nameOrDefault("inputStreamSource")).asInstanceOf[Publisher[ByteString]]
    }

    (pub, bytesReadPromise.future)
  }

  override protected def newInstance(shape: SourceShape[ByteString]): SourceModule[ByteString, Future[Long]] =
    new InputStreamSource(createInputStream, chunkSize, attributes, shape)

  override def withAttributes(attr: Attributes): Module =
    new InputStreamSource(createInputStream, chunkSize, attr, amendShape(attr))
}
