/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.javadsl.model;

import akka.Done;
import akka.actor.ActorSystem;
import akka.japi.function.Procedure;
import akka.stream.ActorMaterializer;
import akka.stream.javadsl.Sink;
import akka.stream.javadsl.Source;
import akka.util.ByteString;
import org.junit.Test;
import org.scalatest.junit.JUnitSuite;

import scala.util.Try;

import java.util.Arrays;
import java.util.concurrent.CompletableFuture;

import static org.junit.Assert.assertEquals;

public class EntityDiscardingTest extends JUnitSuite {

  private ActorSystem sys = ActorSystem.create("test");
  private ActorMaterializer mat = ActorMaterializer.create(sys);
  private Iterable<ByteString> testData = Arrays.asList(ByteString.fromString("abc"), ByteString.fromString("def"));

  @Test
  public void testHttpRequestDiscardEntity() {

    CompletableFuture<Done> f = new CompletableFuture<>();
    Source<ByteString, ?> s = Source.from(testData).alsoTo(Sink.onComplete(completeDone(f)));

    RequestEntity reqEntity = HttpEntities.create(ContentTypes.TEXT_PLAIN_UTF8, s);
    HttpRequest req = HttpRequest.create().withEntity(reqEntity);

    HttpMessage.DiscardedEntity de = req.discardEntityBytes(mat);

    assertEquals(Done.getInstance(), f.join());
    assertEquals(Done.getInstance(), de.completionStage().toCompletableFuture().join());
  }

  @Test
  public void testHttpResponseDiscardEntity() {

    CompletableFuture<Done> f = new CompletableFuture<>();
    Source<ByteString, ?> s = Source.from(testData).alsoTo(Sink.onComplete(completeDone(f)));

    ResponseEntity respEntity = HttpEntities.create(ContentTypes.TEXT_PLAIN_UTF8, s);
    HttpResponse resp = HttpResponse.create().withEntity(respEntity);

    HttpMessage.DiscardedEntity de = resp.discardEntityBytes(mat);

    assertEquals(Done.getInstance(), f.join());
    assertEquals(Done.getInstance(), de.completionStage().toCompletableFuture().join());
  }

  private Procedure<Try<Done>> completeDone(CompletableFuture<Done> p) {
    return new Procedure<Try<Done>>() {
      @Override
      public void apply(Try<Done> t) throws Exception {
        if(t.isSuccess())
          p.complete(Done.getInstance());
        else
          p.completeExceptionally(t.failed().get());
      }
    };
  }
}
