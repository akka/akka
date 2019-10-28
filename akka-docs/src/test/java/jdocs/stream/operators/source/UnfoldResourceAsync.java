/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package jdocs.stream.operators.source;

import akka.Done;
import akka.NotUsed;
import akka.actor.ActorSystem;
import akka.stream.javadsl.Source;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

interface UnfoldResourceAsync {
  // imaginary async API we need to use
  // #unfoldResource-async-api
  interface Database {
    // async query
    CompletionStage<QueryResult> doQuery();
  }

  interface QueryResult {

    // are there more results
    CompletionStage<Boolean> hasMore();

    // async retrieval of each element
    CompletionStage<DatabaseEntry> nextEntry();

    CompletionStage<Void> close();
  }

  interface DatabaseEntry {}

  // #unfoldResource-async-api

  default void unfoldResourceExample() {
    ActorSystem system = null;

    // #unfoldResourceAsync
    // we don't actually have one, it was just made up for the sample
    Database database = null;

    Source<DatabaseEntry, NotUsed> queryResultSource =
        Source.unfoldResourceAsync(
            // open
            database::doQuery,
            // read
            queryResult ->
                queryResult
                    .hasMore()
                    .thenCompose(
                        more -> {
                          if (more) {
                            return queryResult.nextEntry().thenApply(Optional::of);
                          } else {
                            return CompletableFuture.completedFuture(Optional.empty());
                          }
                        }),
            // close
            queryResult -> queryResult.close().thenApply(__ -> Done.done()));

    queryResultSource.runForeach(entry -> System.out.println(entry.toString()), system);
    // #unfoldResourceAsync
  }
}
