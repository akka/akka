/**
 * Copyright (C) 2009-2015 Typesafe Inc. <http://www.typesafe.com>
 */

package docs.persistence;

import akka.actor.*;
import akka.event.EventStreamSpec;
import akka.japi.Function;
import akka.japi.Procedure;
import akka.pattern.BackoffSupervisor;
import akka.persistence.*;
import akka.persistence.query.*;
import akka.persistence.query.javadsl.ReadJournal;
import akka.stream.javadsl.Source;
import akka.util.Timeout;
import docs.persistence.query.MyEventsByTagPublisher;
import scala.collection.Seq;
import scala.concurrent.duration.Duration;
import scala.concurrent.duration.FiniteDuration;
import scala.runtime.BoxedUnit;

import java.io.Serializable;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;

public class PersistenceQueryDocTest {

  final Timeout timeout = Timeout.durationToTimeout(FiniteDuration.create(3, TimeUnit.SECONDS));

  //#my-read-journal
    class MyReadJournal implements ReadJournal {
      private final ExtendedActorSystem system;

    public MyReadJournal(ExtendedActorSystem system) {
      this.system = system;
    }

      final FiniteDuration defaultRefreshInterval = FiniteDuration.create(3, TimeUnit.SECONDS);

      @SuppressWarnings("unchecked")
      public <T, M> Source<T, M> query(Query<T, M> q, Hint... hints) {
        if (q instanceof EventsByTag) {
          final EventsByTag eventsByTag = (EventsByTag) q;
          final String tag = eventsByTag.tag();
          long offset = eventsByTag.offset();

          final Props props = MyEventsByTagPublisher.props(tag, offset, refreshInterval(hints));

          return (Source<T, M>) Source.<EventEnvelope>actorPublisher(props)
                       .mapMaterializedValue(noMaterializedValue());
        } else {
          // unsuported
            return Source.<T>failed(
              new UnsupportedOperationException(
                "Query $unsupported not supported by " + getClass().getName()))
              .mapMaterializedValue(noMaterializedValue());
        }
      }

      private FiniteDuration refreshInterval(Hint[] hints) {
        FiniteDuration ret = defaultRefreshInterval;
        for (Hint hint : hints)
          if (hint instanceof RefreshInterval)
            ret = ((RefreshInterval) hint).interval();
        return ret;
      }

        private <I, M> akka.japi.function.Function<I, M> noMaterializedValue () {
          return param -> (M) null;
        }
  }
    //#my-read-journal
}
