/*
 * Copyright (C) 2016-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package docs.http.javadsl.server.directives;

import akka.NotUsed;
import akka.actor.ActorSystem;
import akka.http.javadsl.ConnectHttp;
import akka.http.javadsl.Http;
import akka.http.javadsl.ServerBinding;
import akka.http.javadsl.model.HttpRequest;
import akka.http.javadsl.model.HttpResponse;
import akka.http.javadsl.model.StatusCode;
import akka.http.javadsl.model.StatusCodes;
import akka.http.javadsl.server.AllDirectives;
import akka.http.javadsl.server.Route;
import akka.http.scaladsl.TestUtils;
import akka.stream.ActorMaterializer;
import akka.stream.javadsl.Flow;
import akka.testkit.TestKit;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;
import org.junit.After;
import org.junit.Ignore;
import org.junit.Test;
import scala.Tuple2;
import scala.Tuple3;
import scala.concurrent.duration.Duration;
import scala.runtime.BoxedUnit;

import java.net.InetSocketAddress;
import java.util.Optional;
import java.util.concurrent.*;

public class TimeoutDirectivesExamplesTest extends AllDirectives {
    //#testSetup
    private final Config testConf = ConfigFactory.parseString("akka.loggers = [\"akka.testkit.TestEventListener\"]\n"
            + "akka.loglevel = ERROR\n"
            + "akka.stdout-loglevel = ERROR\n"
            + "windows-connection-abort-workaround-enabled = auto\n"
            + "akka.log-dead-letters = OFF\n"
            + "akka.http.server.request-timeout = 1000s");
    // large timeout - 1000s (please note - setting to infinite will disable Timeout-Access header
    // and withRequestTimeout will not work)
    
    private final ActorSystem system = ActorSystem.create("TimeoutDirectivesExamplesTest", testConf);

    private final ActorMaterializer materializer = ActorMaterializer.create(system);

    private final Http http = Http.get(system);

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

    private Optional<HttpResponse> runRoute(ActorSystem system, ActorMaterializer materializer, Route route, String routePath) {
        final Tuple3<InetSocketAddress, String, Object> inetaddrHostAndPort = TestUtils.temporaryServerHostnameAndPort("127.0.0.1");
        Tuple2<String, Integer> hostAndPort = new Tuple2<>(
                inetaddrHostAndPort._2(),
                (Integer) inetaddrHostAndPort._3()
        );

        final Flow<HttpRequest, HttpResponse, NotUsed> routeFlow = route.flow(system, materializer);
        final CompletionStage<ServerBinding> binding = http.bindAndHandle(routeFlow, ConnectHttp.toHost(hostAndPort._1(), hostAndPort._2()), materializer);

        final CompletionStage<HttpResponse> responseCompletionStage = http.singleRequest(HttpRequest.create("http://" + hostAndPort._1() + ":" + hostAndPort._2() + "/" + routePath), materializer);

        CompletableFuture<HttpResponse> responseFuture = responseCompletionStage.toCompletableFuture();

        Optional<HttpResponse> responseOptional;
        try {
            responseOptional = Optional.of(responseFuture.get(3, TimeUnit.SECONDS)); // patienceConfig
        } catch (Exception e) {
            responseOptional = Optional.empty();
        }

        shutdown(binding);

        return responseOptional;
    }
    //#

    @After
    public void shutDown() {
        TestKit.shutdownActorSystem(system, Duration.create(1, TimeUnit.SECONDS), false);
    }

    @Test
    public void testRequestTimeoutIsConfigurable() {
        //#withRequestTimeout-plain
        final Duration timeout = Duration.create(1, TimeUnit.SECONDS);
        CompletionStage<String> slowFuture = new CompletableFuture<>();

        final Route route = path("timeout", () ->
                withRequestTimeout(timeout, () -> {
                    return completeOKWithFutureString(slowFuture); // very slow
                })
        );

        // test:
        StatusCode statusCode = runRoute(system, materializer, route, "timeout").get().status();
        assert (StatusCodes.SERVICE_UNAVAILABLE.equals(statusCode));
        //#
    }

    @Test
    public void testRequestWithoutTimeoutCancelsTimeout() {
        //#withoutRequestTimeout-1
        CompletionStage<String> slowFuture = new CompletableFuture<>();

        final Route route = path("timeout", () ->
                withoutRequestTimeout(() -> {
                    return completeOKWithFutureString(slowFuture); // very slow
                })
        );

        // test:
        Boolean receivedReply = runRoute(system, materializer, route, "timeout").isPresent();
        assert (!receivedReply); // timed-out
        //#
    }

    @Test
    public void testRequestTimeoutAllowsCustomResponse() {
        //#withRequestTimeout-with-handler
        final Duration timeout = Duration.create(1, TimeUnit.MILLISECONDS);
        CompletionStage<String> slowFuture = new CompletableFuture<>();

        HttpResponse enhanceYourCalmResponse = HttpResponse.create()
                .withStatus(StatusCodes.ENHANCE_YOUR_CALM)
                .withEntity("Unable to serve response within time limit, please enhance your calm.");

        final Route route = path("timeout", () ->
                withRequestTimeout(timeout, (request) -> enhanceYourCalmResponse, () -> {
                    return completeOKWithFutureString(slowFuture); // very slow
                })
        );

        // test:
        StatusCode statusCode = runRoute(system, materializer, route, "timeout").get().status();
        assert (StatusCodes.ENHANCE_YOUR_CALM.equals(statusCode));
        //#
    }

    // make it compile only to avoid flaking in slow builds
    @Ignore("Compile only test")
    @Test
    public void testRequestTimeoutCustomResponseCanBeAddedSeparately() {
        //#withRequestTimeoutResponse
        final Duration timeout = Duration.create(100, TimeUnit.MILLISECONDS);
        CompletionStage<String> slowFuture = new CompletableFuture<>();

        HttpResponse enhanceYourCalmResponse = HttpResponse.create()
                .withStatus(StatusCodes.ENHANCE_YOUR_CALM)
                .withEntity("Unable to serve response within time limit, please enhance your calm.");

        final Route route = path("timeout", () ->
                withRequestTimeout(timeout, () ->
                        // racy! for a very short timeout like 1.milli you can still get 503
                        withRequestTimeoutResponse((request) -> enhanceYourCalmResponse, () -> {
                            return completeOKWithFutureString(slowFuture); // very slow
                        }))
        );

        // test:
        StatusCode statusCode = runRoute(system, materializer, route, "timeout").get().status();
        assert (StatusCodes.ENHANCE_YOUR_CALM.equals(statusCode));
        //#
    }
}
