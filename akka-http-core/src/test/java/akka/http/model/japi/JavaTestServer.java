/*
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.model.japi;

import akka.actor.ActorSystem;
import akka.japi.function.Function;
import akka.japi.function.Procedure;
import akka.http.engine.server.ServerSettings;
import akka.http.javadsl.model.ws.Message;
import akka.http.javadsl.model.ws.TextMessage;
import akka.http.javadsl.model.ws.Websocket;
import akka.japi.JavaPartialFunction;
import akka.stream.ActorFlowMaterializer;
import akka.stream.FlowMaterializer;
import akka.stream.javadsl.Flow;
import akka.stream.javadsl.Sink;
import akka.stream.javadsl.Source;
import scala.concurrent.Await;
import scala.concurrent.Future;
import scala.concurrent.duration.FiniteDuration;
import scala.runtime.BoxedUnit;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.concurrent.TimeUnit;

public class JavaTestServer {
    public static void main(String[] args) throws Exception {
        ActorSystem system = ActorSystem.create();

        try {
            final FlowMaterializer materializer = ActorFlowMaterializer.create(system);

            Future<ServerBinding> serverBindingFuture =
                    Http.get(system).bindAndHandleSync(
                            new Function<HttpRequest, HttpResponse>() {
                                public HttpResponse apply(HttpRequest request) throws Exception {
                                    System.out.println("Handling request to " + request.getUri());

                                    if (request.getUri().path().equals("/"))
                                        return Websocket.handleWebsocketRequestWith(request, echoMessages(), materializer);
                                    else if (request.getUri().path().equals("/greeter"))
                                        return Websocket.handleWebsocketRequestWith(request, greeter(), materializer);
                                    else
                                        return JavaApiTestCases.handleRequest(request);
                                }
                            }, "localhost", 8080, materializer);

            Await.result(serverBindingFuture, new FiniteDuration(1, TimeUnit.SECONDS)); // will throw if binding fails
            System.out.println("Press ENTER to stop.");
            new BufferedReader(new InputStreamReader(System.in)).readLine();
        } finally {
            system.shutdown();
        }
    }

    public static Flow<Message, Message, BoxedUnit> echoMessages() {
        return Flow.create(); // the identity operation
    }

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
        if (msg.isStrict())
            return TextMessage.create("Hello "+msg.getStrictText());
        else
            return TextMessage.create(Source.single("Hello ").concat(msg.getStreamedText()));
    }
}
