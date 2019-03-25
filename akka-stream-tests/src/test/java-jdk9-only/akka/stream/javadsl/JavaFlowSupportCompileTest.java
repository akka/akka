/*
 * Copyright (C) 2017-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.stream.javadsl;

import akka.NotUsed;
import akka.japi.Pair;
import org.junit.Test;

import java.util.concurrent.Flow;

public class JavaFlowSupportCompileTest {
  @Test
  public void shouldCompile() throws Exception {
    final Flow.Processor<String,String> processor = new Flow.Processor<String, String>() {
      @Override
      public void subscribe(Flow.Subscriber<? super String> subscriber) {}
      @Override
      public void onSubscribe(Flow.Subscription subscription) {}
      @Override
      public void onNext(String item) {}
      @Override
      public void onError(Throwable throwable) {}
      @Override
      public void onComplete() {}
    };


    final Source<String, Flow.Subscriber<String>> stringSubscriberSource = 
      JavaFlowSupport.Source.asSubscriber();
    final Source<String, NotUsed> stringNotUsedSource = 
      JavaFlowSupport.Source.fromPublisher(processor);

    final akka.stream.javadsl.Flow<String, String, NotUsed> stringStringNotUsedFlow = 
      JavaFlowSupport.Flow.fromProcessor(() -> processor);
    final akka.stream.javadsl.Flow<String, String, NotUsed> stringStringNotUsedFlow1 = 
      JavaFlowSupport.Flow.fromProcessorMat(() -> Pair.apply(processor, NotUsed.getInstance()));

    final Sink<String, Flow.Publisher<String>> stringPublisherSink = 
      JavaFlowSupport.Sink.asPublisher(AsPublisher.WITH_FANOUT);
    final Sink<String, NotUsed> stringNotUsedSink = 
      JavaFlowSupport.Sink.fromSubscriber(processor);
  }
}
