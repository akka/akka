/*
 * Copyright (C) 2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.http.impl.engine.server

import akka.http.impl.engine.parsing.ParserOutput
import akka.http.impl.engine.parsing.ParserOutput.{ StrictEntityCreator, EntityStreamError, EntityChunk, StreamedEntityCreator }
import akka.http.impl.engine.server.HttpServerBluePrint.PrepareRequests
import akka.http.scaladsl.model._
import akka.http.scaladsl.settings.ServerSettings
import akka.stream.{ Attributes, ActorMaterializer }
import akka.stream.scaladsl.{ Sink, Source, Flow }
import akka.stream.testkit.{ TestSubscriber, TestPublisher }
import akka.testkit.AkkaSpec
import akka.util.ByteString
import scala.concurrent.duration._

class PrepareRequestsSpec extends AkkaSpec {

  val chunkedStart =
    ParserOutput.RequestStart(
      HttpMethods.GET,
      Uri("http://example.com/"),
      HttpProtocols.`HTTP/1.1`,
      List(),
      StreamedEntityCreator[ParserOutput, RequestEntity] { entityChunks ⇒
        val chunks = entityChunks.collect {
          case EntityChunk(chunk)      ⇒ chunk
          case EntityStreamError(info) ⇒ throw EntityStreamException(info)
        }
        HttpEntity.Chunked(ContentTypes.`application/octet-stream`, HttpEntity.limitableChunkSource(chunks))
      },
      expect100Continue = true,
      closeRequested = false)

  val chunkPart =
    ParserOutput.EntityChunk(HttpEntity.ChunkStreamPart(ByteString("abc")))

  val chunkRequestComplete =
    ParserOutput.MessageEnd

  val strictRequest =
    ParserOutput.RequestStart(
      HttpMethods.GET,
      Uri("http://example.com/"),
      HttpProtocols.`HTTP/1.1`,
      List(),
      StrictEntityCreator(HttpEntity.Strict(ContentTypes.`application/octet-stream`, ByteString("body"))),
      true,
      false)

  "The PrepareRequest stage" should {

    "not fail when there is demand from both streamed entity consumption and regular flow" in {
      implicit val materializer = ActorMaterializer()
      // covers bug #19623 where a reply before the streamed
      // body has been consumed causes pull/push twice
      val inProbe = TestPublisher.manualProbe[ParserOutput.RequestOutput]()
      val upstreamProbe = TestSubscriber.manualProbe[HttpRequest]()

      val stage = Flow.fromGraph(new PrepareRequests(ServerSettings(system)))

      Source.fromPublisher(inProbe)
        .via(stage)
        .to(Sink.fromSubscriber(upstreamProbe))
        .withAttributes(Attributes.inputBuffer(1, 1))
        .run()

      val upstreamSub = upstreamProbe.expectSubscription()
      val inSub = inProbe.expectSubscription()

      // let request with streamed entity through
      upstreamSub.request(1)
      inSub.expectRequest(1)
      inSub.sendNext(chunkedStart)

      val request = upstreamProbe.expectNext()

      // and subscribe to it's streamed entity
      val entityProbe = TestSubscriber.manualProbe[ByteString]()
      request.entity.dataBytes.to(Sink.fromSubscriber(entityProbe))
        .withAttributes(Attributes.inputBuffer(1, 1))
        .run()

      val entitySub = entityProbe.expectSubscription()

      // the bug happens when both the client has signalled demand
      // and the streamed entity has
      upstreamSub.request(1)
      entitySub.request(1)

      // then comes the next chunk from the actual request
      inSub.expectRequest(1)

      // bug would fail stream here with exception
      upstreamProbe.expectNoMsg(100.millis)

      inSub.sendNext(ParserOutput.EntityChunk(HttpEntity.ChunkStreamPart(ByteString("abc"))))
      entityProbe.expectNext()
      entitySub.request(1)
      inSub.sendNext(ParserOutput.MessageEnd)
      entityProbe.expectComplete()

      // the rest of the test covers the saved pull
      // that should go downstream when the streamed entity
      // has reached it's end
      inSub.expectRequest(1)
      inSub.sendNext(strictRequest)

      upstreamProbe.expectNext()

    }

    "not complete running entity stream when upstream cancels" in {
      implicit val materializer = ActorMaterializer()

      val inProbe = TestPublisher.manualProbe[ParserOutput.RequestOutput]()
      val upstreamProbe = TestSubscriber.manualProbe[HttpRequest]()

      val stage = Flow.fromGraph(new PrepareRequests(ServerSettings(system)))

      Source.fromPublisher(inProbe)
        .via(stage)
        .to(Sink.fromSubscriber(upstreamProbe))
        .withAttributes(Attributes.inputBuffer(1, 1))
        .run()

      val upstreamSub = upstreamProbe.expectSubscription()
      val inSub = inProbe.expectSubscription()

      // let request with streamed entity through
      upstreamSub.request(1)
      inSub.expectRequest(1)
      inSub.sendNext(chunkedStart)

      val request = upstreamProbe.expectNext()

      // and subscribe to it's streamed entity
      val entityProbe = TestSubscriber.manualProbe[ByteString]()
      request.entity.dataBytes.to(Sink.fromSubscriber(entityProbe))
        .withAttributes(Attributes.inputBuffer(1, 1))
        .run()

      val entitySub = entityProbe.expectSubscription()

      // user logic cancels flow
      upstreamSub.cancel()

      // but incoming chunks should still end up in entity
      entitySub.request(1)
      inSub.expectRequest(1)
      inSub.sendNext(chunkPart)
      entityProbe.expectNext()

      entitySub.request(1)
      inSub.expectRequest(1)
      inSub.sendNext(chunkRequestComplete)

      // and then when entity is complete, the stage should complete
      entityProbe.expectComplete()

    }

    "complete stage if chunked stream is completed without reaching end of chunks" in {
      // a bit unsure about this, but to document the assumption
      implicit val materializer = ActorMaterializer()

      val inProbe = TestPublisher.manualProbe[ParserOutput.RequestOutput]()
      val upstreamProbe = TestSubscriber.manualProbe[HttpRequest]()

      val stage = Flow.fromGraph(new PrepareRequests(ServerSettings(system)))

      Source.fromPublisher(inProbe)
        .via(stage)
        .to(Sink.fromSubscriber(upstreamProbe))
        .withAttributes(Attributes.inputBuffer(1, 1))
        .run()

      val upstreamSub = upstreamProbe.expectSubscription()
      val inSub = inProbe.expectSubscription()

      // let request with streamed entity through
      upstreamSub.request(1)
      inSub.expectRequest(1)
      inSub.sendNext(chunkedStart)

      val request = upstreamProbe.expectNext()

      // and subscribe to it's streamed entity
      val entityProbe = TestSubscriber.manualProbe[ByteString]()
      request.entity.dataBytes.to(Sink.fromSubscriber(entityProbe))
        .withAttributes(Attributes.inputBuffer(1, 1))
        .run()

      val entitySub = entityProbe.expectSubscription()

      // incoming stream is completed, but we never got the chunk end
      inSub.sendComplete()

      // assumption: should cause stage to complete
      entityProbe.expectComplete()
      upstreamProbe.expectComplete()

    }

    "cancel the stage when the entity stream is canceled" in {
      implicit val materializer = ActorMaterializer()

      val inProbe = TestPublisher.manualProbe[ParserOutput.RequestOutput]()
      val upstreamProbe = TestSubscriber.manualProbe[HttpRequest]()

      val stage = Flow.fromGraph(new PrepareRequests(ServerSettings(system)))

      Source.fromPublisher(inProbe)
        .via(stage)
        .to(Sink.fromSubscriber(upstreamProbe))
        .withAttributes(Attributes.inputBuffer(1, 1))
        .run()

      val upstreamSub = upstreamProbe.expectSubscription()
      val inSub = inProbe.expectSubscription()

      // let request with streamed entity through
      upstreamSub.request(1)
      inSub.expectRequest(1)
      inSub.sendNext(chunkedStart)

      val request = upstreamProbe.expectNext()

      // and subscribe to it's streamed entity
      val entityProbe = TestSubscriber.manualProbe[ByteString]()
      request.entity.dataBytes.to(Sink.fromSubscriber(entityProbe))
        .withAttributes(Attributes.inputBuffer(1, 1))
        .run()

      val entitySub = entityProbe.expectSubscription()

      // user logic cancels entity stream
      entitySub.cancel()

      inSub.expectCancellation()
    }
  }

}
