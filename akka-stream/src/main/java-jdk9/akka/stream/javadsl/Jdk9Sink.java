/*
 * Copyright (C) 2017 Lightbend Inc. <http://www.lightbend.com/>
 */

package akka.stream.javadsl;

/**
 * TODO java doc
 */
public interface Jdk9Sink {
  default <T> Sink<T, java.util.concurrent.Flow.Publisher<T>> asFlowPublisher(AsPublisher fanout) {
    return new akka.stream.javadsl.Sink(
      akka.stream.scaladsl.Jdk9Sink.asJavaFlowPublisher(fanout.equals(AsPublisher.WITH_FANOUT))
    );
  }
}
