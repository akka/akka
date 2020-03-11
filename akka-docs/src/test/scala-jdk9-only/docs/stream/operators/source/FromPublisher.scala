/*
 * Copyright (C) 2020 Lightbend Inc. <https://www.lightbend.com>
 */

package docs.stream.operators.source;

//#imports
import java.util.concurrent.Flow.Subscriber;
import java.util.concurrent.Flow.Publisher;

import akka.NotUsed;
import akka.stream.scaladsl.Source;
import akka.stream.scaladsl.JavaFlowSupport;

//#imports

object FromPublisher {
    case class Row(name: String)

    class DatabaseClient {
        def fetchRows(): Publisher[Row] = ???
    }

    val databaseClient: DatabaseClient = ???

    // #example
    val names: Source[String, NotUsed] =
      // rowSource can be re-used, since it will start a new
      // query for each materialization, fully supporting backpressure
      // for each materialized stream:
      JavaFlowSupport.Source.fromPublisher(databaseClient.fetchRows())
        .map(row => row.name);
    //#example
}
