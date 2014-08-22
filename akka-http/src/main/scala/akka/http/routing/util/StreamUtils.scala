/*
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.routing.util

import java.io.InputStream
import java.util.concurrent.atomic.AtomicInteger

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
  val nameCounter = new AtomicInteger
  def fromInputStream(inputStream: InputStream, defaultChunkSize: Int = 65536)(implicit materializer: FlowMaterializer): Publisher[ByteString] = {
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

    ActorPublisher[ByteString](materializer.asInstanceOf[ActorBasedFlowMaterializer].actorOf(props, "fromInputStream-" + nameCounter.incrementAndGet()))
  }

  def awaitAllElements[T](data: Publisher[T])(implicit materializer: FlowMaterializer): immutable.Seq[T] =
    Await.result(Flow(data).fold(Vector.empty[T])(_ :+ _).toFuture(), 1.second)

  implicit class HttpEntityEnhancements(entity: HttpEntity) {
    def sliceData(start: Long, length: Long)(implicit materializer: FlowMaterializer): HttpEntity = entity match {
      case HttpEntity.Strict(tpe, data) ⇒
        // should be checked before
        assert(start >= 0 && start <= Int.MaxValue)
        assert(length >= 0 && length <= Int.MaxValue)
        HttpEntity.Strict(tpe, data.slice(start.toInt, length.toInt))
      case HttpEntity.Default(tpe, _, data) ⇒
        HttpEntity.Default(tpe, length, data.slice(start, length))
    }
  }

  implicit class ByteStringPublisherEnhancements(underlying: Publisher[ByteString]) {
    def slice(start: Long, length: Long)(implicit materializer: FlowMaterializer): Publisher[ByteString] =
      Flow(underlying).transform(new Transformer[ByteString, ByteString] {
        type State = Transformer[ByteString, ByteString]

        def skipping = new State {
          var toSkip = start
          def onNext(element: ByteString): immutable.Seq[ByteString] =
            if (element.length < toSkip) {
              // keep skipping
              toSkip -= element.length
              Nil
            } else {
              become(taking(length))
              // toSkip <= element.length <= Int.MaxValue
              currentState.onNext(element.drop(toSkip.toInt))
            }
        }
        def taking(initiallyRemaining: Long) = new State {
          var remaining: Long = initiallyRemaining
          def onNext(element: ByteString): immutable.Seq[ByteString] = {
            val data = element.take(math.min(remaining, Int.MaxValue).toInt)
            remaining -= data.size
            if (remaining <= 0) become(finishing)
            data :: Nil
          }
        }
        def finishing = new State {
          override def isComplete: Boolean = true
          def onNext(element: ByteString): immutable.Seq[ByteString] =
            throw new IllegalStateException("onNext called on complete stream")
        }

        var currentState: State = if (start > 0) skipping else taking(length)
        def become(state: State): Unit = currentState = state

        override def isComplete: Boolean = currentState.isComplete
        def onNext(element: ByteString): immutable.Seq[ByteString] = currentState.onNext(element)
        override def onTermination(e: Option[Throwable]): immutable.Seq[ByteString] = currentState.onTermination(e)
      }).toPublisher()
  }

  /**
   * Returns a copy of the given entity with the ByteString chunks of this entity mapped by the given function `f`.
   * After the last chunked has been mapped by `f`, the `finish` method is called and its result is added
   * to the data stream.
   *
   * FIXME: should we move this into HttpEntity?
   * FIXME: add tests
   */
  def mapEntityDataBytes(entity: HttpEntity, f: ByteString ⇒ ByteString, finish: () ⇒ ByteString)(implicit materializer: FlowMaterializer): HttpEntity = {
    def transformer[T](postProcessing: ByteString ⇒ T): Transformer[ByteString, T] =
      new Transformer[ByteString, T] {
        def onNext(element: ByteString): immutable.Seq[T] = postProcessing(f(element)) :: Nil

        override def onTermination(e: Option[Throwable]): immutable.Seq[T] =
          if (e.isEmpty) {
            val last = finish()
            if (last.nonEmpty) postProcessing(last) :: Nil
            else Nil
          } else super.onTermination(e)
      }

    entity match {
      case HttpEntity.Strict(tpe, data) ⇒ HttpEntity.Strict(tpe, f(data) ++ finish())
      case HttpEntity.Default(tpe, length, data) ⇒
        val chunks = Flow(data).transform(transformer(Chunk(_): ChunkStreamPart)).toPublisher()

        HttpEntity.Chunked(tpe, chunks)
      case HttpEntity.CloseDelimited(tpe, data) ⇒
        val newData = Flow(data).transform(transformer(identity)).toPublisher()

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
          }).toPublisher()

        HttpEntity.Chunked(tpe, newChunks)
    }
  }
}
