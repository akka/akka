/**
 * Copyright (C) 2015 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.impl.io

import java.io.{ File, InputStream }

import akka.stream._
import akka.stream.impl.StreamLayout.Module
import akka.stream.impl.{ ErrorPublisher, SourceModule }
import akka.util.ByteString
import org.reactivestreams._

import scala.concurrent.{ Future, Promise }

/**
 * INTERNAL API
 * Creates simple synchronous (Java 6 compatible) Source backed by the given file.
 */
private[akka] final class SynchronousFileSource(f: File, chunkSize: Int, val attributes: OperationAttributes, shape: SourceShape[ByteString])
  extends SourceModule[ByteString, Future[Long]](shape) {
  override def create(context: MaterializationContext) = {
    val mat = ActorFlowMaterializer.downcast(context.materializer)
    val settings = mat.effectiveSettings(context.effectiveAttributes)

    val bytesReadPromise = Promise[Long]()
    val props = SynchronousFilePublisher.props(f, bytesReadPromise, chunkSize, settings.initialInputBufferSize, settings.maxInputBufferSize)
    val dispatcher = IOSettings.fileIoDispatcher(context)

    val ref = mat.actorOf(context, props.withDispatcher(dispatcher))

    (akka.stream.actor.ActorPublisher[ByteString](ref), bytesReadPromise.future)
  }

  override protected def newInstance(shape: SourceShape[ByteString]): SourceModule[ByteString, Future[Long]] =
    new SynchronousFileSource(f, chunkSize, attributes, shape)

  override def withAttributes(attr: OperationAttributes): Module =
    new SynchronousFileSource(f, chunkSize, attr, amendShape(attr))
}

/**
 * INTERNAL API
 * Source backed by the given input stream.
 */
private[akka] final class InputStreamSource(createInputStream: () ⇒ InputStream, chunkSize: Int, val attributes: OperationAttributes, shape: SourceShape[ByteString])
  extends SourceModule[ByteString, Future[Long]](shape) {
  override def create(context: MaterializationContext) = {
    val mat = ActorFlowMaterializer.downcast(context.materializer)
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

  override def withAttributes(attr: OperationAttributes): Module =
    new InputStreamSource(createInputStream, chunkSize, attr, amendShape(attr))
}
