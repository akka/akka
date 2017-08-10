/*
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */

package docs.http.javadsl;

//##single-request-in-actor-example

import akka.actor.AbstractActor;
import akka.http.javadsl.Http;
import akka.http.javadsl.model.HttpRequest;
import akka.http.javadsl.model.HttpResponse;
import akka.japi.pf.ReceiveBuilder;
import akka.stream.ActorMaterializer;
import akka.stream.Materializer;
import scala.concurrent.ExecutionContextExecutor;

import java.util.concurrent.CompletionStage;

import static akka.pattern.PatternsCS.pipe;

public class SingleRequestInActorExample extends AbstractActor {
    final Http http = Http.get(context().system());
    final ExecutionContextExecutor dispatcher = context().dispatcher();
    final Materializer materializer = ActorMaterializer.create(context());

    public SingleRequestInActorExample() { // syntax changes slightly in Akka 2.5, see the migration guide
        receive(ReceiveBuilder
                .match(String.class, url -> pipe(fetch(url), dispatcher).to(self()))
                .build());
    }

    CompletionStage<HttpResponse> fetch(String url) {
        return http.singleRequest(HttpRequest.create(url), materializer);
    }
}
//#single-request-in-actor-example
