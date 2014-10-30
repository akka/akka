/**
 * Copyright (C) 2014 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.stream.javadsl;

import akka.actor.ActorRef;
import akka.actor.ActorSystem;
import akka.dispatch.Foreach;
import akka.dispatch.Futures;
import akka.dispatch.OnSuccess;
import akka.japi.Pair;
import akka.japi.Util;
import akka.stream.FlowMaterializer;
import akka.stream.MaterializerSettings;
import akka.stream.OverflowStrategy;
import akka.stream.Transformer;
import akka.stream.javadsl.japi.*;
import akka.stream.testkit.AkkaSpec;
import akka.testkit.JavaTestKit;
import org.junit.ClassRule;
import org.junit.Test;
import org.reactivestreams.Publisher;
import scala.Option;
import scala.collection.immutable.Seq;
import scala.concurrent.Await;
import scala.concurrent.Future;
import scala.concurrent.duration.Duration;
import scala.concurrent.duration.FiniteDuration;
import scala.runtime.BoxedUnit;
import scala.util.Try;

import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;

public class SinkTest {

  @ClassRule
  public static AkkaJUnitActorSystemResource actorSystemResource = new AkkaJUnitActorSystemResource("FlowTest",
      AkkaSpec.testConf());

  final ActorSystem system = actorSystemResource.getSystem();

  final MaterializerSettings settings = new MaterializerSettings(2, 4, 2, 4, "akka.test.stream-dispatcher");
  final FlowMaterializer materializer = FlowMaterializer.create(settings, system);

  @Test
  public void mustBeAbleToUseFanoutPublisher() throws Exception {
    KeyedSink<Object, Publisher<Object>> pubSink = Sink.fanoutPublisher(2, 2);
    Publisher<Object> publisher = Source.from(new ArrayList<Object>()).runWith(pubSink, materializer);
  }

}
