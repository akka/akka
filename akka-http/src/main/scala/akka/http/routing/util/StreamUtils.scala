/*
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.routing.util

import java.io.InputStream

import scala.collection.immutable

import scala.concurrent.Await
import scala.concurrent.duration._

import akka.util.ByteString
import akka.actor.Props

import akka.stream.impl.{ ActorBasedFlowMaterializer, ActorPublisher, SimpleCallbackPublisher }
import akka.stream.scaladsl.Flow
import akka.stream.{ Transformer, Stop, FlowMaterializer }

import org.reactivestreams.Publisher

/**
 * Some utils that should become superfluous ASAP by akka-streams providing the
 * functionality.
 */
private[http] object StreamUtils {
  def fromInputStream(inputStream: InputStream, materializer: FlowMaterializer, defaultChunkSize: Int = 65536): Publisher[ByteString] = {
    def props: Props = {
      def f(): ByteString = {
        val chunk = new Array[Byte](defaultChunkSize)
        val read = inputStream.read(chunk)
        if (read == -1) {
          inputStream.close()
          throw Stop
        } else ByteString.fromArray(chunk, 0, read)
      }
      SimpleCallbackPublisher.props(materializer.settings, f)
    }

    ActorPublisher[ByteString](materializer.asInstanceOf[ActorBasedFlowMaterializer].context.actorOf(props))
  }

  def awaitAllElements[T](data: Publisher[T], materializer: FlowMaterializer): immutable.Seq[T] =
    Await.result(Flow(data).fold(Vector.empty[T])(_ :+ _).toFuture(materializer), 1.second)

  implicit class ByteStringPublisherEnhancements(underlying: Publisher[ByteString]) {
    def slice(start: Long, length: Long, materializer: FlowMaterializer): Publisher[ByteString] = akka.http.routing.FIXME
    /*Flow(underlying).transform(new Transformer[ByteString, ByteString] {
        var nextIndex = 0

        def onNext(element: ByteString): immutable.Seq[U] = ???
      }).toPublisher(materializer)*/
  }
}
