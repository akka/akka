/*
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.routing.util

import java.io.InputStream

import akka.http.model.HttpEntity
import akka.http.model.HttpEntity.{ LastChunk, Chunk, ChunkStreamPart }

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

  /**
   * Returns a copy of this entity the ByteString chunks of this entity mapped by the given function `f`.
   * After the last chunked has been mapped by `f`, the `finish` method is called and its result is added
   * to the data stream.
   *
   * FIXME: should we move this into HttpEntity?
   * FIXME: add tests
   */
  def mapEntityDataBytes(entity: HttpEntity, f: ByteString ⇒ ByteString, finish: () ⇒ ByteString, materializer: FlowMaterializer): HttpEntity =
    entity match {
      case HttpEntity.Strict(tpe, data) ⇒ HttpEntity.Strict(tpe, f(data) ++ finish())
      case HttpEntity.Default(tpe, length, data) ⇒
        val chunks =
          Flow(data).transform(new Transformer[ByteString, ChunkStreamPart] {
            def onNext(element: ByteString): immutable.Seq[ChunkStreamPart] = Chunk(f(element)) :: Nil
            override def onTermination(e: Option[scala.Throwable]): immutable.Seq[ChunkStreamPart] =
              if (e.isEmpty) {
                val last = finish()
                if (last.nonEmpty) Chunk(last) :: Nil
                else Nil
              } else super.onTermination(e)
          }).toPublisher(materializer)

        HttpEntity.Chunked(tpe, chunks)
      case HttpEntity.CloseDelimited(tpe, data) ⇒
        val newData =
          Flow(data).transform(new Transformer[ByteString, ByteString] {
            def onNext(element: ByteString): immutable.Seq[ByteString] = f(element) :: Nil
            override def onTermination(e: Option[scala.Throwable]): immutable.Seq[ByteString] =
              if (e.isEmpty) {
                val last = finish()
                if (last.nonEmpty) last :: Nil
                else Nil
              } else super.onTermination(e)
          }).toPublisher(materializer)

        HttpEntity.CloseDelimited(tpe, newData)
      case HttpEntity.Chunked(tpe, chunks) ⇒
        val newChunks =
          Flow(chunks).transform(new Transformer[ChunkStreamPart, ChunkStreamPart] {
            var sentLastChunk = false

            def onNext(element: ChunkStreamPart): immutable.Seq[ChunkStreamPart] = element match {
              case Chunk(data, ext) ⇒ Chunk(f(data), ext) :: Nil
              case l: LastChunk ⇒
                sentLastChunk = true
                Chunk(finish()) :: l :: Nil
            }
            override def onTermination(e: Option[scala.Throwable]): immutable.Seq[ChunkStreamPart] =
              if (e.isEmpty && !sentLastChunk) {
                val last = finish()
                if (last.nonEmpty) Chunk(last) :: Nil
                else Nil
              } else super.onTermination(e)
          }).toPublisher(materializer)

        HttpEntity.Chunked(tpe, newChunks)
    }
}
