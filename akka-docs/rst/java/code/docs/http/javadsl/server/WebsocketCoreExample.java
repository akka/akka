/*
 * Copyright (C) 2009-2015 Typesafe Inc. <http://www.typesafe.com>
 */

package docs.http.javadsl.server;

//#websocket-example-using-core
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.concurrent.TimeUnit;

import scala.concurrent.Await;
import scala.concurrent.Future;
import scala.concurrent.duration.FiniteDuration;
import scala.runtime.BoxedUnit;

import akka.japi.Function;
import akka.japi.JavaPartialFunction;

import akka.stream.ActorMaterializer;
import akka.stream.Materializer;
import akka.stream.javadsl.Flow;
import akka.stream.javadsl.Source;

import akka.actor.ActorSystem;
import akka.http.javadsl.Http;
import akka.http.javadsl.ServerBinding;
import akka.http.javadsl.model.HttpRequest;
import akka.http.javadsl.model.HttpResponse;
import akka.http.javadsl.model.ws.Message;
import akka.http.javadsl.model.ws.TextMessage;
import akka.http.javadsl.model.ws.Websocket;

public class WebsocketCoreExample {
    //#websocket-handling
    public static HttpResponse handleRequest(HttpRequest request) {
        System.out.println("Handling request to " + request.getUri());

        if (request.getUri().path().equals("/greeter"))
            return Websocket.handleWebsocketRequestWith(request, greeter());
        else
            return HttpResponse.create().withStatus(404);
    }
    //#websocket-handling

    public static void main(String[] args) throws Exception {
        ActorSystem system = ActorSystem.create();

        try {
            final Materializer materializer = ActorMaterializer.create(system);

            Future<ServerBinding> serverBindingFuture =
                Http.get(system).bindAndHandleSync(
                    new Function<HttpRequest, HttpResponse>() {
                        public HttpResponse apply(HttpRequest request) throws Exception {
                            return handleRequest(request);
                        }
                    }, "localhost", 8080, materializer);

            // will throw if binding fails
            Await.result(serverBindingFuture, new FiniteDuration(1, TimeUnit.SECONDS));
            System.out.println("Press ENTER to stop.");
            new BufferedReader(new InputStreamReader(System.in)).readLine();
        } finally {
            system.shutdown();
        }
    }

    //#websocket-handler
    /**
     * A handler that treats incoming messages as a name,
     * and responds with a greeting to that name
     */
    public static Flow<Message, Message, BoxedUnit> greeter() {
        return
            Flow.<Message>create()
                .collect(new JavaPartialFunction<Message, Message>() {
                    @Override
                    public Message apply(Message msg, boolean isCheck) throws Exception {
                        if (isCheck)
                            if (msg.isText()) return null;
                            else throw noMatch();
                        else
                            return handleTextMessage(msg.asTextMessage());
                    }
                });
    }
    public static TextMessage handleTextMessage(TextMessage msg) {
        if (msg.isStrict()) // optimization that directly creates a simple response...
            return TextMessage.create("Hello "+msg.getStrictText());
        else // ... this would suffice to handle all text messages in a streaming fashion
            return TextMessage.create(Source.single("Hello ").concat(msg.getStreamedText()));
    }
    //#websocket-handler
}
//#websocket-example-using-core