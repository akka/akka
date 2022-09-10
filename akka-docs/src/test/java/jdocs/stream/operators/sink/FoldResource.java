/*
 * Copyright (C) 2022 Lightbend Inc. <https://www.lightbend.com>
 */

package jdocs.stream.operators.sink;

import akka.actor.ActorSystem;
import akka.stream.javadsl.Sink;
import akka.stream.javadsl.Source;

import java.net.URL;
import java.util.Arrays;

public interface FoldResource {
  // #foldResource-blocking-api
  interface DBDriver {
    Connection create(URL url, String userName, String password);
  }

  interface Connection {
    void close();
  }

  interface Database {
    // blocking insert
    DBResult doInsert(Connection connection, String query);
  }

  interface DBResult {
    boolean isSuccess();

    int affectedCount();
  }

  // #foldResource-blocking-api

  default void foldResourceExample() {
    final ActorSystem system = null;
    final URL url = null;
    final String userName = "Akka";
    final String password = "Hakking";
    final DBDriver dbDriver = null;
    // #foldResource
    // some database for JVM
    final Database db = null;
    Source.from(
            Arrays.asList(
                "INSERT INTO t1 (a, b, c) VALUES (1, 2, 3);",
                "INSERT INTO t2 (a, b, c) VALUES (4, 5, 6);"))
        .runWith(
            Sink.foldResource(
                () -> dbDriver.create(url, userName, password),
                (connection, query) -> db.doInsert(connection, query),
                Connection::close),
            system);
    // #foldResource
  }
}
