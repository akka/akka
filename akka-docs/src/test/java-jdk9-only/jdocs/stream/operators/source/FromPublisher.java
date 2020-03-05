/*
 * Copyright (C) 2019-2020 Lightbend Inc. <https://www.lightbend.com>
 */

package jdocs.stream.operators.source;

//#imports
import java.util.concurrent.Flow.Publisher;

import akka.NotUsed;
import akka.stream.javadsl.Source;
import akka.stream.javadsl.JavaFlowSupport;

//#imports

import org.apache.commons.lang.NotImplementedException;

public interface FromPublisher {
    static class JavaFlowSupport {
        public static final class Source {
            public
            // #api
            static <T> akka.stream.javadsl.Source<T, NotUsed> fromPublisher(Publisher<T> publisher)
            // #api
            {
                return akka.stream.javadsl.JavaFlowSupport.Source.<T>fromPublisher(publisher);
            }
        }
    }

    static class Row {
        public String getField(String fieldName) {
            throw new NotImplementedException();
        }
    }

    static class DatabaseClient {
        Publisher<Row> fetchRows() {
            throw new NotImplementedException();
        }
    }

    DatabaseClient databaseClient = null;

    // #example
    class Example {
        public Source<String, NotUsed> names() {
            // rowSource can be re-used, since it will start a new
            // query for each materialization, fully supporting backpressure
            // for each materialized stream:
            return JavaFlowSupport.Source.<Row>fromPublisher(databaseClient.fetchRows())
                .map(row -> row.getField("name"));
        }
    }
    // #example
}
