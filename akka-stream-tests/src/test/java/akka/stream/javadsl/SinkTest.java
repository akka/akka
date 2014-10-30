/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.javadsl;

import java.util.ArrayList;
import java.util.List;

import org.junit.ClassRule;
import org.junit.Test;
import org.reactivestreams.Publisher;

import scala.concurrent.Await;
import scala.concurrent.Future;
import scala.concurrent.duration.Duration;
import akka.actor.ActorSystem;
import akka.stream.FlowMaterializer;
import akka.stream.MaterializerSettings;
import akka.stream.testkit.AkkaSpec;

public class SinkTest {

  @ClassRule
  public static AkkaJUnitActorSystemResource actorSystemResource = new AkkaJUnitActorSystemResource("FlowTest",
      AkkaSpec.testConf());

  final ActorSystem system = actorSystemResource.getSystem();

  final MaterializerSettings settings = new MaterializerSettings(2, 4, 2, 4, "akka.test.stream-dispatcher");
  final FlowMaterializer materializer = FlowMaterializer.create(settings, system);

  @Test
  public void mustBeAbleToUseFanoutPublisher() throws Exception {
    final KeyedSink<Object, Publisher<Object>> pubSink = Sink.fanoutPublisher(2, 2);
    final Publisher<Object> publisher = Source.from(new ArrayList<Object>()).runWith(pubSink, materializer);
  }
  
  @Test
  public void mustBeAbleToUseFuture() throws Exception {
    final KeyedSink<Integer, Future<Integer>> futSink = Sink.future();
    final List<Integer> list = new ArrayList<Integer>();
    list.add(1);
    final Future<Integer> future = Source.from(list).runWith(futSink, materializer);
    assert Await.result(future, Duration.create("1 second")).equals(1);
  }

}
