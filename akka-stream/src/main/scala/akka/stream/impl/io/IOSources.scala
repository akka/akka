/**
 * Copyright (C) 2015-2017 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.stream.impl.io

import java.io.InputStream
import java.nio.file.Path

import akka.stream._
import akka.stream.ActorAttributes.Dispatcher
import akka.stream.IOResult
import akka.stream.impl.StreamLayout.Module
import akka.stream.impl.Stages.DefaultAttributes.IODispatcher
import akka.stream.impl.{ ErrorPublisher, SourceModule }
import akka.util.ByteString
import org.reactivestreams._
import scala.concurrent.{ Future, Promise }

/**
 * INTERNAL API
 * Creates simple synchronous Source backed by the given file.
 */
private[akka] final class FileSource(f: Path, chunkSize: Int, val attributes: Attributes, shape: SourceShape[ByteString])
  extends SourceModule[ByteString, Future[IOResult]](shape) {
  require(chunkSize > 0, "chunkSize must be greater than 0")
  override def create(context: MaterializationContext) = {
    // FIXME rewrite to be based on GraphStage rather than dangerous downcasts
    val materializer = ActorMaterializerHelper.downcast(context.materializer)
    val settings = materializer.effectiveSettings(context.effectiveAttributes)

    val ioResultPromise = Promise[IOResult]()
    val props = FilePublisher.props(f, ioResultPromise, chunkSize, settings.initialInputBufferSize, settings.maxInputBufferSize)
    val dispatcher = context.effectiveAttributes.get[Dispatcher](IODispatcher).dispatcher

    val ref = materializer.actorOf(context, props.withDispatcher(dispatcher))

    (akka.stream.actor.ActorPublisher[ByteString](ref), ioResultPromise.future)
  }

  override protected def newInstance(shape: SourceShape[ByteString]): SourceModule[ByteString, Future[IOResult]] =
    new FileSource(f, chunkSize, attributes, shape)

  override def withAttributes(attr: Attributes): Module =
    new FileSource(f, chunkSize, attr, amendShape(attr))

  override protected def label: String = s"FileSource($f, $chunkSize)"
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
