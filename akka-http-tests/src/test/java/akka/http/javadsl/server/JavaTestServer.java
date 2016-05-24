/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.http.javadsl.server;

import akka.actor.ActorSystem;
import akka.http.javadsl.ConnectHttp;
import akka.http.javadsl.Http;
import akka.http.javadsl.ServerBinding;
import akka.http.javadsl.model.HttpRequest;
import akka.http.javadsl.model.HttpResponse;
import akka.http.javadsl.model.StatusCodes;
import akka.stream.ActorMaterializer;
import akka.stream.javadsl.Flow;
import scala.concurrent.duration.Duration;
import scala.runtime.BoxedUnit;

import java.util.Optional;
import java.util.concurrent.*;
import java.util.function.Function;

public class JavaTestServer extends AllDirectives { // or import static Directives.*;

  public Route createRoute() {
    final Duration timeout = Duration.create(1, TimeUnit.SECONDS);

    final Route index = path("", () ->
        withRequestTimeout(timeout, this::mkTimeoutResponse, () -> {
          silentSleep(5000); // too long, trigger failure
          return complete(index());
        })
      );

    final Function<Optional<ProvidedCredentials>, Optional<String>> handleAuth = (maybeCreds) -> {
      if (maybeCreds.isPresent() && maybeCreds.get().verify("pa$$word")) // some secure hash + check
        return Optional.of(maybeCreds.get().identifier());
      else return Optional.empty();
    };

    final Route secure = path("secure", () ->
        authenticateBasic("My basic secure site", handleAuth, (login) ->
          complete(String.format("Hello, %s!", login))
        )
      );

    final Route ping = path("ping", () ->
      complete("PONG!")
      );

    final Route crash = path("crash", () ->
      path("scala", () -> completeOKWithFutureString(akka.dispatch.Futures.<String>failed(new Exception("Boom!")))).orElse(
      path("java", () -> completeOKWithFutureString(CompletableFuture.<String>supplyAsync(() -> { throw new RuntimeException("Boom!"); }))))
    );

    final Route inner = path("inner", () ->
      getFromResourceDirectory("someDir")
    );


    return get(() ->
      index.orElse(secure).orElse(ping).orElse(crash).orElse(inner)
    );
  }

  private void silentSleep(int millis) {
    try {
      Thread.sleep(millis);
    } catch (InterruptedException e) {
      throw new RuntimeException(e);
    }
  }

  private HttpResponse mkTimeoutResponse(HttpRequest request) {
    return HttpResponse.create()
      .withStatus(StatusCodes.ENHANCE_YOUR_CALM)
      .withEntity("Unable to serve response within time limit, please enchance your calm.");
  }

  private String index() {
    return "    <html>\n" +
      "      <body>\n" +
      "        <h1>Say hello to <i>akka-http-core</i>!</h1>\n" +
      "        <p>Defined resources:</p>\n" +
      "        <ul>\n" +
      "          <li><a href=\"/ping\">/ping</a></li>\n" +
      "          <li><a href=\"/secure\">/secure</a> Use any username and '&lt;username&gt;-password' as credentials</li>\n" +
      "          <li><a href=\"/crash\">/crash</a></li>\n" +
      "        </ul>\n" +
      "      </body>\n" +
      "    </html>\n";
  }

  public static void main(String[] args) throws InterruptedException {
    final JavaTestServer server = new JavaTestServer();
    server.run();
  }

  private void run() throws InterruptedException {
    final ActorSystem system = ActorSystem.create();
    final ActorMaterializer mat = ActorMaterializer.create(system);

    final Flow<HttpRequest, HttpResponse, ?> flow = createRoute().flow(system, mat);
    final CompletionStage<ServerBinding> binding =
      Http.get(system).bindAndHandle(flow, ConnectHttp.toHost("127.0.0.1"), mat);

    System.console().readLine("Press [ENTER] to quit...");
    shutdown(binding);
  }

  private CompletionStage<Void> shutdown(CompletionStage<ServerBinding> binding) {
    return binding.thenAccept(b -> {
      System.out.println(String.format("Unbinding from %s", b.localAddress()));

      final CompletionStage<BoxedUnit> unbound = b.unbind();
      try {
        unbound.toCompletableFuture().get(3, TimeUnit.SECONDS); // block...
      } catch (TimeoutException | InterruptedException | ExecutionException e) {
        throw new RuntimeException(e);
      }
    });
  }
}
