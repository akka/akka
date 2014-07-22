/*
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.model.japi;

import static akka.pattern.Patterns.ask;

import akka.actor.ActorRef;
import akka.actor.ActorSystem;
import akka.dispatch.Foreach;
import akka.japi.Function;
import akka.japi.Procedure;
import akka.stream.FlowMaterializer;
import akka.stream.MaterializerSettings;
import akka.stream.javadsl.Flow;
import scala.concurrent.Future;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;

public abstract class JavaTestServer {
    public static void main(String[] args) throws IOException, InterruptedException {
        ActorSystem system = ActorSystem.create();

        MaterializerSettings settings = MaterializerSettings.create();
        final FlowMaterializer materializer = FlowMaterializer.create(settings, system);

        ActorRef httpManager = Http.get(system).manager();
        Future<Object> binding = ask(httpManager, Http.bind("localhost", 8080), 1000);
        binding.foreach(new Foreach<Object>() {
            @Override
            public void each(Object result) throws Throwable {
                ServerBinding binding = (ServerBinding) result;
                System.out.println("Bound to " + binding.localAddress());

                Flow.create(binding.getConnectionStream()).foreach(new Procedure<IncomingConnection>() {
                    @Override
                    public void apply(IncomingConnection conn) throws Exception {
                        System.out.println("New incoming connection from " + conn.remoteAddress());

                        Flow.create(conn.getRequestPublisher())
                                .map(new Function<HttpRequest, HttpResponse>() {
                                    @Override
                                    public HttpResponse apply(HttpRequest request) throws Exception {
                                        System.out.println("Handling request to " + request.getUri());
                                        return JavaApiTestCases.handleRequest(request);
                                    }
                                })
                                .produceTo(materializer, conn.getResponseSubscriber());
                    }
                }).consume(materializer);
            }
        }, system.dispatcher());

        System.out.println("Press ENTER to stop.");
        new BufferedReader(new InputStreamReader(System.in)).readLine();

        system.shutdown();
    }
}
