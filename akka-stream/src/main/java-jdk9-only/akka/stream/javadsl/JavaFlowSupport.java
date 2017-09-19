/*
 * Copyright (C) 2017 Lightbend Inc. <http//www.lightbend.com/>
 */

package akka.stream.javadsl;

import akka.NotUsed;
import akka.japi.Pair;
import akka.japi.Creator;
import akka.stream.impl.JavaFlowAndRsConverters;

/**
 * For use only with `JDK 9+`.
 * <p>
 * Provides support for `java.util.concurrent.Flow.*` interfaces which mirror the Reactive Streams
 * interfaces from `org.reactivestreams`. See <http//www.reactive-streams.org/>(reactive-streams.org).
 */
public final class JavaFlowSupport {

  private static final NotUsed NotUsed = akka.NotUsed.getInstance();

  private JavaFlowSupport() {
    throw new RuntimeException("No instances allowed!");
  }

  public static final class Source {
    private Source() {
      throw new RuntimeException("No instances allowed!");
    }

    /**
     * Helper to create <<Source>> from <<java.util.concurrent.Flow.Publisher>>.
     * <p>
     * Construct a transformation starting with given publisher. The transformation steps
     * are executed by a series of <<java.util.concurrent.Flow.Processor>> instances
     * that mediate the flow of elements downstream and the propagation of
     * back-pressure upstream.
     * <p>
     * See also <<Source.fromPublisher>> if wanting to integrate with <<org.reactivestreams.Publisher>> instead
     * (which carries the same semantics, however existed before RS's inclusion in Java 9).
     */
    public static <T> akka.stream.javadsl.Source<T, NotUsed> fromPublisher(java.util.concurrent.Flow.Publisher<T> publisher) {
      return akka.stream.javadsl.Source.<T>fromPublisher(JavaFlowAndRsConverters.asRs(publisher));
    }

    /**
     * Creates a `Source` that is materialized as a <<java.util.concurrent.Flow.Subscriber>>
     * <p>
     * See also <<Source.asSubscriber>> if wanting to integrate with <<org.reactivestreams.Subscriber>> instead
     * (which carries the same semantics, however existed before RS's inclusion in Java 9).
     */
    public static <T> akka.stream.javadsl.Source<T, java.util.concurrent.Flow.Subscriber<T>> asSubscriber() {
      return akka.stream.javadsl.Source.<T>asSubscriber().mapMaterializedValue(JavaFlowAndRsConverters::asJava);
    }
  }

  public static final class Flow {
    private Flow() {
      throw new RuntimeException("No instances allowed!");
    }

    /**
     * Creates a Flow from a {@link java.util.concurrent.Flow.Processor}
     */
    public static <I, O> akka.stream.javadsl.Flow<I, O, NotUsed> fromProcessor(Creator<java.util.concurrent.Flow.Processor<I, O>> processorFactory) throws Exception {
      return fromProcessorMat(() -> Pair.apply(processorFactory.create(), NotUsed));
    }


    /**
     * Creates a Flow from a {@link java.util.concurrent.Flow.Processor>> and returns a materialized value.
     */
    public static <I, O, M> akka.stream.javadsl.Flow<I, O, M> fromProcessorMat(
      akka.japi.Creator<akka.japi.Pair<java.util.concurrent.Flow.Processor<I, O>, M>> processorFactory) throws Exception {
      final Pair<java.util.concurrent.Flow.Processor<I, O>, M> value = processorFactory.create();
      final java.util.concurrent.Flow.Processor<I, O> processor = value.first();
      final M mat = value.second();

      return akka.stream.javadsl.Flow.fromProcessorMat(() ->
        akka.japi.Pair.apply(JavaFlowAndRsConverters.asRs(processor), mat)
      );

    }

    /**
     * Converts this Flow to a <<RunnableGraph>> that materializes to a Reactive Streams <<java.util.concurrent.Flow.Processor>>
     * which implements the operations encapsulated by this Flow. Every materialization results in a new Processor
     * instance, i.e. the returned <<RunnableGraph>> is reusable.
     *
     * @return A <<RunnableGraph>> that materializes to a Processor when run() is called on it.
     */
    public static <In, Out, Mat> akka.stream.javadsl.RunnableGraph<java.util.concurrent.Flow.Processor<In, Out>> toProcessor(akka.stream.javadsl.Flow<In, Out, Mat> flow) {
      final akka.stream.javadsl.Source<In, java.util.concurrent.Flow.Subscriber<In>> source = JavaFlowSupport.Source.<In>asSubscriber();
      final akka.stream.javadsl.Sink<Out, java.util.concurrent.Flow.Publisher<Out>> sink = JavaFlowSupport.Sink.<Out>asPublisher(AsPublisher.WITHOUT_FANOUT);

      // have to jump though scaladsl for the toMat because type inference of the Keep.both
      return
        source.via(flow).toMat(sink, Keep.both())
          .mapMaterializedValue(pair -> {
            final java.util.concurrent.Flow.Subscriber<In> sub = pair.first();
            final java.util.concurrent.Flow.Publisher<Out> pub = pair.second();

            return new java.util.concurrent.Flow.Processor<In, Out>() {
              @Override public void onError(Throwable t) { sub.onError(t); }
              @Override public void onSubscribe(java.util.concurrent.Flow.Subscription s) { sub.onSubscribe(s); }
              @Override public void onComplete() { sub.onComplete(); }
              @Override public void onNext(In t) { sub.onNext(t); }
              @Override public void subscribe(java.util.concurrent.Flow.Subscriber<? super Out> s) { pub.subscribe(s); }
            };
          });
    }
  }

  /**
   * {@link akka.stream.javadsl.Sink} factories operating with {@code java.util.concurrent.Flow.*} interfaces.
   */
  public static final class Sink {
    private Sink() {
      throw new RuntimeException("No instances allowed!");
    }

    /**
     * A `Sink` that materializes into a <<java.util.concurrent.Flow.Publisher>>.
     * <p>
     * If `fanout` is `WITH_FANOUT`, the materialized `Publisher` will support multiple `Subscriber`s and
     * the size of the `inputBuffer` configured for this stage becomes the maximum number of elements that
     * the fastest <<java.util.concurrent.Flow.Subscriber>> can be ahead of the slowest one before slowing
     * the processing down due to back pressure.
     * <p>
     * If `fanout` is `WITHOUT_FANOUT` then the materialized `Publisher` will only support a single `Subscriber` and
     * reject any additional `Subscriber`s.
     */
    public static <T> akka.stream.javadsl.Sink<T, java.util.concurrent.Flow.Publisher<T>> asPublisher(AsPublisher fanout) {
      return akka.stream.javadsl.Sink.<T>asPublisher(fanout).mapMaterializedValue(JavaFlowAndRsConverters::asJava);
    }

      /**
       * Helper to create <<Sink>> from <<java.util.concurrent.Flow.Subscriber>>.
       */
      public static <T> akka.stream.javadsl.Sink<T, NotUsed> fromSubscriber(java.util.concurrent.Flow.Subscriber<T> s) {
        return akka.stream.javadsl.Sink.fromSubscriber(JavaFlowAndRsConverters.asRs(s));
      }

  }

}
