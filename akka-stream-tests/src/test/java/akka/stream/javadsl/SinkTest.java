/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.javadsl;

import java.util.Arrays;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.ClassRule;
import org.junit.Test;
import org.reactivestreams.Publisher;
import scala.concurrent.Await;
import scala.concurrent.Future;
import scala.concurrent.duration.Duration;
import akka.stream.StreamTest;
import akka.japi.function.Function2;
import akka.stream.testkit.AkkaSpec;
import akka.testkit.JavaTestKit;

public class SinkTest extends StreamTest {
  public SinkTest() {
    super(actorSystemResource);
  }

  @ClassRule
  public static AkkaJUnitActorSystemResource actorSystemResource = new AkkaJUnitActorSystemResource("FlowTest",
      AkkaSpec.testConf());

  @Test
  public void mustBeAbleToUseFanoutPublisher() throws Exception {
    final Sink<Object, Publisher<Object>> pubSink = Sink.fanoutPublisher(2, 2);
    @SuppressWarnings("unused")
    final Publisher<Object> publisher = Source.from(new ArrayList<Object>()).runWith(pubSink, materializer);
  }
  
  @Test
  public void mustBeAbleToUseFuture() throws Exception {
    final Sink<Integer, Future<Integer>> futSink = Sink.head();
    final List<Integer> list = Collections.singletonList(1);
    final Future<Integer> future = Source.from(list).runWith(futSink, materializer);
    assert Await.result(future, Duration.create("1 second")).equals(1);
  }

  @Test
  public void mustBeAbleToUseFold() throws Exception {
    Sink<Integer, Future<Integer>> foldSink = Sink.fold(0, new Function2<Integer, Integer, Integer>() {
      @Override public Integer apply(Integer arg1, Integer arg2) throws Exception {
        return arg1 + arg2;
      }
    });
    @SuppressWarnings("unused")
    Future<Integer> integerFuture = Source.from(new ArrayList<Integer>()).runWith(foldSink, materializer);
  }
  
  @Test
  public void mustBeAbleToUseActorRefSink() throws Exception {
    final JavaTestKit probe = new JavaTestKit(system);
    final Sink<Integer, ?> actorRefSink = Sink.actorRef(probe.getRef(), "done");
    Source.from(Arrays.asList(1, 2, 3)).runWith(actorRefSink, materializer);
    probe.expectMsgEquals(1);
    probe.expectMsgEquals(2);
    probe.expectMsgEquals(3);
    probe.expectMsgEquals("done");
  }

}
