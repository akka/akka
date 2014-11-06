/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.javadsl;

import akka.actor.ActorSystem;
import akka.stream.FlowMaterializer;
import akka.stream.MaterializerSettings;
import akka.stream.javadsl.japi.Function2;
import akka.stream.testkit.AkkaSpec;
import org.junit.ClassRule;
import org.junit.Test;
import org.reactivestreams.Publisher;
import scala.concurrent.Await;
import scala.concurrent.Future;
import scala.concurrent.duration.Duration;

import java.util.ArrayList;
import java.util.List;

public class SinkTest {

  @ClassRule
  public static AkkaJUnitActorSystemResource actorSystemResource = new AkkaJUnitActorSystemResource("FlowTest",
      AkkaSpec.testConf());

  final ActorSystem system = actorSystemResource.getSystem();

  final MaterializerSettings settings = MaterializerSettings.create(system);
  final FlowMaterializer materializer = FlowMaterializer.create(settings, system);

  @Test
  public void mustBeAbleToUseFanoutPublisher() throws Exception {
    final KeyedSink<Object, Publisher<Object>> pubSink = Sink.fanoutPublisher(2, 2);
    final Publisher<Object> publisher = Source.from(new ArrayList<Object>()).runWith(pubSink, materializer);
  }
  
  @Test
  public void mustBeAbleToUseFuture() throws Exception {
    final KeyedSink<Integer, Future<Integer>> futSink = Sink.head();
    final List<Integer> list = new ArrayList<Integer>();
    list.add(1);
    final Future<Integer> future = Source.from(list).runWith(futSink, materializer);
    assert Await.result(future, Duration.create("1 second")).equals(1);
  }

  @Test
  public void mustBeAbleToUseFold() throws Exception {
    KeyedSink<Integer, Future<Integer>> foldSink = Sink.fold(0, new Function2<Integer, Integer, Integer>() {
      @Override public Integer apply(Integer arg1, Integer arg2) throws Exception {
        return arg1 + arg2;
      }
    });
    Future<Integer> integerFuture = Source.from(new ArrayList<Integer>()).runWith(foldSink, materializer);
  }

}
