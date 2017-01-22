/*
 * Copyright (C) 2016-2017 Lightbend Inc. <http://www.lightbend.com>
 */
package docs.http.javadsl.server.directives;

import akka.NotUsed;
import akka.actor.ActorSystem;
import akka.dispatch.ExecutionContexts;
import akka.event.Logging;
import akka.event.LoggingAdapter;
import akka.http.javadsl.model.ContentTypes;
import akka.http.javadsl.model.HttpEntities;
import akka.http.javadsl.model.HttpEntity;
import akka.http.javadsl.model.HttpMethods;
import akka.http.javadsl.model.HttpRequest;
import akka.http.javadsl.model.HttpResponse;
import akka.http.javadsl.model.ResponseEntity;
import akka.http.javadsl.model.StatusCodes;
import akka.http.javadsl.model.headers.RawHeader;
import akka.http.javadsl.model.headers.Server;
import akka.http.javadsl.model.headers.ProductVersion;
import akka.http.javadsl.settings.RoutingSettings;
import akka.http.javadsl.testkit.JUnitRouteTest;
import akka.http.javadsl.server.*;
import akka.japi.pf.PFBuilder;
import akka.stream.ActorMaterializer;
import akka.stream.ActorMaterializerSettings;
import akka.stream.javadsl.FileIO;
import akka.stream.javadsl.Sink;
import akka.stream.javadsl.Source;
import akka.util.ByteString;
import org.junit.Ignore;
import org.junit.Test;
import scala.concurrent.ExecutionContextExecutor;
import scala.concurrent.duration.FiniteDuration;

import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.StreamSupport;

public class BasicDirectivesExamplesTest extends JUnitRouteTest {

  @Test
  public void testExtract() {
    //#extract
    final Route route = extract(
      ctx -> ctx.getRequest().getUri().toString().length(),
      len -> complete("The length of the request URI is " + len)
    );

    // tests:
    testRoute(route).run(HttpRequest.GET("/abcdef"))
      .assertEntity("The length of the request URI is 25");
    //#extract
  }

  @Test
  public void testExtractLog() {
    //#extractLog
    final Route route = extractLog(log -> {
      log.debug("I'm logging things in much detail..!");
      return complete("It's amazing!");
    });

    // tests:
    testRoute(route).run(HttpRequest.GET("/abcdef"))
      .assertEntity("It's amazing!");
    //#extractLog
  }

  @Test
  public void testWithMaterializer() {
    //#withMaterializer
    final ActorMaterializerSettings settings = ActorMaterializerSettings.create(system());
    final ActorMaterializer special = ActorMaterializer.create(settings, system(), "special");

    final Route sample = path("sample", () ->
      extractMaterializer(mat ->
        onSuccess(() ->
          // explicitly use the materializer:
          Source.single("Materialized by " + mat.hashCode() + "!")
            .runWith(Sink.head(), mat), this::complete
        )
      )
    );

    final Route route = route(
      pathPrefix("special", () ->
        withMaterializer(special, () -> sample) // `special` materializer will be used
      ),
      sample // default materializer will be used
    );

    // tests:
    testRoute(route).run(HttpRequest.GET("/sample"))
      .assertEntity("Materialized by " + materializer().hashCode()+ "!");
    testRoute(route).run(HttpRequest.GET("/special/sample"))
      .assertEntity("Materialized by " + special.hashCode()+ "!");
    //#withMaterializer
  }

  @Test
  public void testExtractMaterializer() {
    //#extractMaterializer
    final Route route = path("sample", () ->
      extractMaterializer(mat ->
        onSuccess(() ->
          // explicitly use the materializer:
          Source.single("Materialized by " + mat.hashCode() + "!")
            .runWith(Sink.head(), mat), this::complete
        )
      )
    ); // default materializer will be used

    testRoute(route).run(HttpRequest.GET("/sample"))
      .assertEntity("Materialized by " + materializer().hashCode()+ "!");
    //#extractMaterializer
  }

  @Test
  public void testWithExecutionContext() {
    //#withExecutionContext

    final ExecutionContextExecutor special =
      ExecutionContexts.fromExecutor(Executors.newFixedThreadPool(1));

    final Route sample = path("sample", () ->
      extractExecutionContext(executor ->
        onSuccess(() ->
          CompletableFuture.supplyAsync(() ->
            "Run on " + executor.hashCode() + "!", executor
          ), this::complete
        )
      )
    );

    final Route route = route(
      pathPrefix("special", () ->
        // `special` execution context will be used
        withExecutionContext(special, () -> sample)
      ),
      sample // default execution context will be used
    );

    // tests:
    testRoute(route).run(HttpRequest.GET("/sample"))
      .assertEntity("Run on " + system().dispatcher().hashCode() + "!");
    testRoute(route).run(HttpRequest.GET("/special/sample"))
      .assertEntity("Run on " + special.hashCode() + "!");
    //#withExecutionContext
  }

  @Test
  public void testExtractExecutionContext() {
    //#extractExecutionContext
    final Route route = path("sample", () ->
      extractExecutionContext(executor ->
        onSuccess(() ->
          CompletableFuture.supplyAsync(
            // uses the `executor` ExecutionContext
            () -> "Run on " + executor.hashCode() + "!", executor
          ), str -> complete(str)
        )
      )
    );

    // tests:
    testRoute(route).run(HttpRequest.GET("/sample"))
      .assertEntity("Run on " + system().dispatcher().hashCode() + "!");
    //#extractExecutionContext
  }

  @Test
  public void testWithLog() {
    //#withLog
    final LoggingAdapter special = Logging.getLogger(system(), "SpecialRoutes");

    final Route sample = path("sample", () ->
      extractLog(log -> {
        final String msg = "Logging using " + log + "!";
        log.debug(msg);
        return complete(msg);
      }
      )
    );

    final Route route = route(
      pathPrefix("special", () ->
        withLog(special, () -> sample)
      ),
      sample
    );

    // tests:
    testRoute(route).run(HttpRequest.GET("/sample"))
      .assertEntity("Logging using " + system().log() + "!");

    testRoute(route).run(HttpRequest.GET("/special/sample"))
      .assertEntity("Logging using " + special + "!");
    //#withLog
  }

  @Ignore("Ignore compile-only test")
  @Test
  public void testWithSettings() {
    //#withSettings
    final RoutingSettings special =
      RoutingSettings
        .create(system().settings().config())
        .withFileIODispatcher("special-io-dispatcher");

    final Route sample = path("sample", () -> {
      // internally uses the configured fileIODispatcher:
      // ContentTypes.APPLICATION_JSON, source
      final Source<ByteString, Object> source =
        FileIO.fromPath(Paths.get("example.json"))
          .mapMaterializedValue(completionStage -> (Object) completionStage);
      return complete(
        HttpResponse.create()
          .withEntity(HttpEntities.create(ContentTypes.APPLICATION_JSON, source))
      );
    });

    final Route route = get(() ->
      route(
        pathPrefix("special", () ->
          // `special` file-io-dispatcher will be used to read the file
          withSettings(special, () -> sample)
        ),
        sample // default file-io-dispatcher will be used to read the file
      )
    );

    // tests:
    testRoute(route).run(HttpRequest.GET("/special/sample"))
      .assertEntity("{}");
    testRoute(route).run(HttpRequest.GET("/sample"))
      .assertEntity("{}");
    //#withSettings
  }

  @Test
  public void testMapResponse() {
    //#mapResponse
    final Route route = mapResponse(
      response -> response.withStatus(StatusCodes.BAD_GATEWAY),
      () -> complete("abc")
    );

    // tests:
    testRoute(route).run(HttpRequest.GET("/abcdef?ghi=12"))
      .assertStatusCode(StatusCodes.BAD_GATEWAY);
    //#mapResponse
  }

  @Test
  public void testMapResponseAdvanced() {
    //#mapResponse-advanced
    class ApiRoute {

      private final ActorSystem system;

      private final LoggingAdapter log;

      private final HttpEntity nullJsonEntity =
        HttpEntities.create(ContentTypes.APPLICATION_JSON, "{}");

      public ApiRoute(ActorSystem system) {
        this.system = system;
        this.log = Logging.getLogger(system, "ApiRoutes");
      }

      private HttpResponse nonSuccessToEmptyJsonEntity(HttpResponse response) {
        if (response.status().isSuccess()) {
          return response;
        } else {
          log.warning(
            "Dropping response entity since response status code was: " + response.status());
          return response.withEntity((ResponseEntity) nullJsonEntity);
        }
      }

      /** Wrapper for all of our JSON API routes */
      private Route apiRoute(Supplier<Route> innerRoutes) {
        return mapResponse(this::nonSuccessToEmptyJsonEntity, innerRoutes);
      }
    }

    final ApiRoute api = new ApiRoute(system());

    final Route route = api.apiRoute(() ->
      get(() -> complete(StatusCodes.INTERNAL_SERVER_ERROR))
    );

    // tests:
    testRoute(route).run(HttpRequest.GET("/"))
      .assertEntity("{}");
    //#mapResponse-advanced
  }

  @Test
  public void testMapRouteResult() {
    //#mapRouteResult
    // this directive is a joke, don't do that :-)
    final Route route = mapRouteResult(r -> {
      if (r instanceof Complete) {
        final HttpResponse response = ((Complete) r).getResponse();
        return RouteResults.complete(response.withStatus(200));
      } else {
        return r;
      }
    }, () -> complete(StatusCodes.ACCEPTED));

    // tests:
    testRoute(route).run(HttpRequest.GET("/"))
      .assertStatusCode(StatusCodes.OK);
    //#mapRouteResult
  }

  @Test
  public void testMapRouteResultFuture() {
    //#mapRouteResultFuture
    final Route route = mapRouteResultFuture(cr ->
      cr.exceptionally(t -> {
        if (t instanceof IllegalArgumentException) {
          return RouteResults.complete(
            HttpResponse.create().withStatus(StatusCodes.INTERNAL_SERVER_ERROR));
        } else {
          return null;
        }
      }).thenApply(rr -> {
        if (rr instanceof Complete) {
          final HttpResponse res = ((Complete) rr).getResponse();
          return RouteResults.complete(
            res.addHeader(Server.create(ProductVersion.create("MyServer", "1.0"))));
        } else {
          return rr;
        }
      }), () -> complete("Hello world!"));

    // tests:
    testRoute(route).run(HttpRequest.GET("/"))
      .assertStatusCode(StatusCodes.OK)
      .assertHeaderExists(Server.create(ProductVersion.create("MyServer", "1.0")));
    //#mapRouteResultFuture
  }

  @Test
  public void testMapResponseEntity() {
    //#mapResponseEntity
    final Function<ResponseEntity, ResponseEntity> prefixEntity = entity -> {
      if (entity instanceof HttpEntity.Strict) {
        final HttpEntity.Strict strict = (HttpEntity.Strict) entity;
        return HttpEntities.create(
          strict.getContentType(),
          ByteString.fromString("test").concat(strict.getData()));
      } else {
        throw new IllegalStateException("Unexpected entity type");
      }
    };

    final Route route = mapResponseEntity(prefixEntity, () -> complete("abc"));

    testRoute(route).run(HttpRequest.GET("/"))
      .assertEntity("testabc");
    //#mapResponseEntity
  }

  @Test
  public void testMapResponseHeaders() {
    //#mapResponseHeaders
    // adds all request headers to the response
    final Route echoRequestHeaders = extract(
      ctx -> ctx.getRequest().getHeaders(),
      headers -> respondWithHeaders(headers, () -> complete("test"))
    );

    final Route route = mapResponseHeaders(headers -> {
      headers.removeIf(header -> header.lowercaseName().equals("id"));
      return headers;
    }, () -> echoRequestHeaders);

    // tests:
    testRoute(route).run(HttpRequest.GET("/").addHeaders(
      Arrays.asList(RawHeader.create("id", "12345"),RawHeader.create("id2", "67890"))))
      .assertHeaderKindNotExists("id")
      .assertHeaderExists("id2", "67890");
    //#mapResponseHeaders
  }

  @Ignore("Not implemented yet")
  @Test
  public void testMapInnerRoute() {
    //#mapInnerRoute
    // TODO: implement mapInnerRoute
    //#mapInnerRoute
  }

  @Test
  public void testMapRejections() {
    //#mapRejections
    // ignore any rejections and replace them by AuthorizationFailedRejection
    final Route route = mapRejections(
      rejections -> Collections.singletonList((Rejection) Rejections.authorizationFailed()),
      () -> path("abc", () -> complete("abc"))
    );

    // tests:
    runRouteUnSealed(route, HttpRequest.GET("/"))
      .assertRejections(Rejections.authorizationFailed());
    testRoute(route).run(HttpRequest.GET("/abc"))
      .assertStatusCode(StatusCodes.OK);
    //#mapRejections
  }

  @Test
  public void testRecoverRejections() {
    //#recoverRejections
    final Function<Optional<ProvidedCredentials>, Optional<Object>> neverAuth =
      creds -> Optional.empty();
    final Function<Optional<ProvidedCredentials>, Optional<Object>> alwaysAuth =
      creds -> Optional.of("id");

    final Route originalRoute = pathPrefix("auth", () ->
      route(
        path("never", () ->
          authenticateBasic("my-realm", neverAuth, obj ->  complete("Welcome to the bat-cave!"))
        ),
        path("always", () ->
          authenticateBasic("my-realm", alwaysAuth, obj -> complete("Welcome to the secret place!"))
        )
      )
    );

    final Function<Iterable<Rejection>, Boolean> existsAuthenticationFailedRejection =
      rejections ->
        StreamSupport.stream(rejections.spliterator(), false)
          .anyMatch(r -> r instanceof AuthenticationFailedRejection);

    final Route route = recoverRejections(rejections -> {
      if (existsAuthenticationFailedRejection.apply(rejections)) {
        return RouteResults.complete(
          HttpResponse.create().withEntity("Nothing to see here, move along."));
      } else if (!rejections.iterator().hasNext()) { // see "Empty Rejections" for more details
        return RouteResults.complete(
            HttpResponse.create().withStatus(StatusCodes.NOT_FOUND)
              .withEntity("Literally nothing to see here."));
      } else {
        return RouteResults.rejected(rejections);
      }
    }, () -> originalRoute);

    // tests:
    testRoute(route).run(HttpRequest.GET("/auth/never"))
      .assertStatusCode(StatusCodes.OK)
      .assertEntity("Nothing to see here, move along.");
    testRoute(route).run(HttpRequest.GET("/auth/always"))
      .assertStatusCode(StatusCodes.OK)
      .assertEntity("Welcome to the secret place!");
    testRoute(route).run(HttpRequest.GET("/auth/does_not_exist"))
      .assertStatusCode(StatusCodes.NOT_FOUND)
      .assertEntity("Literally nothing to see here.");
    //#recoverRejections
  }

  @Test
  public void testRecoverRejectionsWith() {
    //#recoverRejectionsWith
    final Function<Optional<ProvidedCredentials>, Optional<Object>> neverAuth =
      creds -> Optional.empty();

    final Route originalRoute = pathPrefix("auth", () ->
      path("never", () ->
        authenticateBasic("my-realm", neverAuth, obj ->  complete("Welcome to the bat-cave!"))
      )
    );

    final Function<Iterable<Rejection>, Boolean> existsAuthenticationFailedRejection =
      rejections ->
        StreamSupport.stream(rejections.spliterator(), false)
          .anyMatch(r -> r instanceof AuthenticationFailedRejection);

    final Route route = recoverRejectionsWith(
      rejections -> CompletableFuture.supplyAsync(() -> {
        if (existsAuthenticationFailedRejection.apply(rejections)) {
          return RouteResults.complete(
              HttpResponse.create().withEntity("Nothing to see here, move along."));
        } else {
          return RouteResults.rejected(rejections);
        }
    }), () -> originalRoute);

    // tests:
    testRoute(route).run(HttpRequest.GET("/auth/never"))
      .assertStatusCode(StatusCodes.OK)
      .assertEntity("Nothing to see here, move along.");
    //#recoverRejectionsWith
  }

  @Test
  public void testMapRequest() {
    //#mapRequest
    final Route route = mapRequest(req ->
      req.withMethod(HttpMethods.POST), () ->
      extractRequest(req -> complete("The request method was " + req.method().name()))
    );

    // tests:
    testRoute(route).run(HttpRequest.GET("/"))
      .assertEntity("The request method was POST");
    //#mapRequest
  }

  @Test
  public void testMapRequestContext() {
    //#mapRequestContext
    final Route route = mapRequestContext(ctx ->
      ctx.withRequest(HttpRequest.create().withMethod(HttpMethods.POST)), () ->
      extractRequest(req -> complete(req.method().value()))
    );

    // tests:
    testRoute(route).run(HttpRequest.GET("/abc/def/ghi"))
      .assertEntity("POST");
    //#mapRequestContext
  }

  @Test
  public void testMapRouteResult0() {
    //#mapRouteResult
    final Route route = mapRouteResult(rr -> {
      final Iterable<Rejection> rejections = Collections.singletonList(Rejections.authorizationFailed());
      return RouteResults.rejected(rejections);
    }, () -> complete("abc"));

    // tests:
    runRouteUnSealed(route, HttpRequest.GET("/"))
      .assertRejections(Rejections.authorizationFailed());
    //#mapRouteResult
  }

  public static final class MyCustomRejection implements CustomRejection {}

  @Test
  public void testMapRouteResultPF() {
    //#mapRouteResultPF
    final Route route = mapRouteResultPF(
      new PFBuilder<RouteResult, RouteResult>()
        .match(Rejected.class, rejected -> {
          final Iterable<Rejection> rejections =
            Collections.singletonList(Rejections.authorizationFailed());
          return RouteResults.rejected(rejections);
        }).build(), () -> reject(new MyCustomRejection()));

    // tests:
    runRouteUnSealed(route, HttpRequest.GET("/"))
      .assertRejections(Rejections.authorizationFailed());
    //#mapRouteResultPF
  }

  @Test
  public void testMapRouteResultWithPF() {
    //#mapRouteResultWithPF
    final Route route = mapRouteResultWithPF(
      new PFBuilder<RouteResult, CompletionStage<RouteResult>>()
      .match(Rejected.class, rejected -> CompletableFuture.supplyAsync(() -> {
        final Iterable<Rejection> rejections =
          Collections.singletonList(Rejections.authorizationFailed());
        return RouteResults.rejected(rejections);
      })
    ).build(), () -> reject(new MyCustomRejection()));

    // tests:
    runRouteUnSealed(route, HttpRequest.GET("/"))
      .assertRejections(Rejections.authorizationFailed());
    //#mapRouteResultWithPF
  }

  @Test
  public void testMapRouteResultWith() {
    //#mapRouteResultWith
    final Route route = mapRouteResultWith(rr -> CompletableFuture.supplyAsync(() -> {
      if (rr instanceof Rejected) {
        final Iterable<Rejection> rejections =
          Collections.singletonList(Rejections.authorizationFailed());
        return RouteResults.rejected(rejections);
      } else {
        return rr;
      }
    }), () -> reject(new MyCustomRejection()));

    // tests:
    runRouteUnSealed(route, HttpRequest.GET("/"))
      .assertRejections(Rejections.authorizationFailed());
    //#mapRouteResultWith
  }

  @Test
  public void testPass() {
    //#pass
    final Route route = pass(() -> complete("abc"));

    // tests:
    testRoute(route).run(HttpRequest.GET("/"))
      .assertEntity("abc");
    //#pass
  }

  private Route providePrefixedStringRoute(String value) {
    return provide("prefix:" + value, this::complete);
  }

  @Test
  public void testProvide() {
    //#provide
    final Route route = providePrefixedStringRoute("test");

    // tests:
    testRoute(route).run(HttpRequest.GET("/"))
      .assertEntity("prefix:test");
    //#provide
  }

  @Ignore("Test failed")
  @Test
  public void testCancelRejections() {
    //#cancelRejections
    final Predicate<Rejection> isMethodRejection = p -> p instanceof MethodRejection;
    final Route route = cancelRejections(
      isMethodRejection, () -> post(() -> complete("Result"))
    );

    // tests:
    runRouteUnSealed(route, HttpRequest.GET("/"))
      .assertRejections();
    //#cancelRejections
  }

  @Ignore("Test failed")
  @Test
  public void testCancelRejection() {
    //#cancelRejection
    final Route route = cancelRejection(Rejections.method(HttpMethods.POST), () ->
      post(() ->  complete("Result"))
    );

    // tests:
    runRouteUnSealed(route, HttpRequest.GET("/"))
      .assertRejections();
    //#cancelRejection
  }

  @Test
  public void testExtractRequest() {
    //#extractRequest
    final Route route = extractRequest(request ->
      complete("Request method is " + request.method().name() +
                 " and content-type is " + request.entity().getContentType())
    );

    // tests:
    testRoute(route).run(HttpRequest.POST("/").withEntity("text"))
      .assertEntity("Request method is POST and content-type is text/plain; charset=UTF-8");
    testRoute(route).run(HttpRequest.GET("/"))
      .assertEntity("Request method is GET and content-type is none/none");
    //#extractRequest
  }

  @Test
  public void testExtractSettings() {
    //#extractSettings
    final Route route = extractSettings(settings ->
      complete("RoutingSettings.renderVanityFooter = " + settings.getRenderVanityFooter())
    );

    // tests:
    testRoute(route).run(HttpRequest.GET("/"))
      .assertEntity("RoutingSettings.renderVanityFooter = true");
    //#extractSettings
  }

  @Test
  public void testMapSettings() {
    //#mapSettings
    final Route route = mapSettings(settings ->
      settings.withFileGetConditional(false), () ->
      extractSettings(settings ->
        complete("RoutingSettings.fileGetConditional = " + settings.getFileGetConditional())
      )
    );

    // tests:
    testRoute(route).run(HttpRequest.GET("/"))
      .assertEntity("RoutingSettings.fileGetConditional = false");
    //#mapSettings
  }

  @Test
  public void testExtractRequestContext() {
    //#extractRequestContext
    final Route route = extractRequestContext(ctx -> {
      ctx.getLog().debug("Using access to additional context available, like the logger.");
      final HttpRequest request = ctx.getRequest();
      return complete("Request method is " + request.method().name() +
                        " and content-type is " + request.entity().getContentType());
    });

    // tests:
    testRoute(route).run(HttpRequest.POST("/").withEntity("text"))
      .assertEntity("Request method is POST and content-type is text/plain; charset=UTF-8");
    testRoute(route).run(HttpRequest.GET("/"))
      .assertEntity("Request method is GET and content-type is none/none");
    //#extractRequestContext
  }

  @Test
  public void testExtractParserSettings() {
    //#extractParserSettings
    final Route route = extractParserSettings(parserSettings ->
      complete("URI parsing mode is " + parserSettings.getUriParsingMode())
    );

    // tests:
    testRoute(route).run(HttpRequest.GET("/"))
      .assertEntity("URI parsing mode is Strict");
    //#extractParserSettings
  }

  @Test
  public void testExtractMatchedPath() {
    //#extractMatchedPath
    final Route route = pathPrefix("abc", () -> extractMatchedPath(this::complete));

    // tests:
    testRoute(route).run(HttpRequest.GET("/abc")).assertEntity("/abc");
    testRoute(route).run(HttpRequest.GET("/abc/xyz")).assertEntity("/abc");
    //#extractMatchedPath
  }

  @Test
  public void testExtractUri() {
    //#extractUri
    final Route route = extractUri(uri ->
      complete("Full URI: " + uri)
    );

    // tests:
    // tests are executed with the host assumed to be "example.com"
    testRoute(route).run(HttpRequest.GET("/"))
      .assertEntity("Full URI: http://example.com/");
    testRoute(route).run(HttpRequest.GET("/test"))
      .assertEntity("Full URI: http://example.com/test");
    //#extractUri
  }

  @Test
  public void testMapUnmatchedPath() {
    //#mapUnmatchedPath
    final Function<String, String> ignore456 = path -> {
      int slashPos = path.indexOf("/");
      if (slashPos != -1) {
        String head = path.substring(0, slashPos);
        String tail = path.substring(slashPos);
        if (head.length() <= 3) {
          return tail;
        } else {
          return path.substring(3);
        }
      } else {
        return path;
      }
    };

    final Route route = pathPrefix("123", () ->
      mapUnmatchedPath(ignore456, () ->
        path("abc", () ->
          complete("Content")
        )
      )
    );

    // tests:
    testRoute(route).run(HttpRequest.GET("/123/abc"))
      .assertEntity("Content");
    testRoute(route).run(HttpRequest.GET("/123456/abc"))
      .assertEntity("Content");
    //#mapUnmatchedPath
  }

  @Test
  public void testExtractUnmatchedPath() {
    //#extractUnmatchedPath
    final Route route = pathPrefix("abc", () ->
      extractUnmatchedPath(remaining ->
        complete("Unmatched: '" + remaining + "'")
      )
    );

    // tests:
    testRoute(route).run(HttpRequest.GET("/abc"))
      .assertEntity("Unmatched: ''");
    testRoute(route).run(HttpRequest.GET("/abc/456"))
      .assertEntity("Unmatched: '/456'");
    //#extractUnmatchedPath
  }

  @Test
  public void testExtractRequestEntity() {
    //#extractRequestEntity
    final Route route = extractRequestEntity(entity ->
      complete("Request entity content-type is " + entity.getContentType())
    );

    // tests:
    testRoute(route).run(
      HttpRequest.POST("/abc")
        .withEntity(HttpEntities.create(ContentTypes.TEXT_PLAIN_UTF8, "req"))
    ).assertEntity("Request entity content-type is text/plain; charset=UTF-8");
    //#extractRequestEntity
  }

  @Test
  public void testExtractDataBytes() {
    //#extractDataBytes
    final Route route = extractDataBytes(data -> {
      final CompletionStage<Integer> sum = data.runFold(0, (acc, i) ->
        acc + Integer.valueOf(i.utf8String()), materializer());
      return onSuccess(() -> sum, s ->
        complete(HttpResponse.create().withEntity(HttpEntities.create(s.toString()))));
    });

    // tests:
    final Iterator iterator = Arrays.asList(
      ByteString.fromString("1"),
      ByteString.fromString("2"),
      ByteString.fromString("3")).iterator();
    final Source<ByteString, NotUsed> dataBytes = Source.fromIterator(() -> iterator);

    testRoute(route).run(
      HttpRequest.POST("abc")
        .withEntity(HttpEntities.create(ContentTypes.TEXT_PLAIN_UTF8, dataBytes))
    ).assertEntity("6");
    //#extractDataBytes
  }

  @Test
  public void testExtractStrictEntity() {
    //#extractStrictEntity
    final FiniteDuration timeout = FiniteDuration.create(3, TimeUnit.SECONDS);
    final Route route = extractStrictEntity(timeout, strict ->
      complete(strict.getData().utf8String())
    );

    // tests:
    final Iterator iterator = Arrays.asList(
      ByteString.fromString("1"),
      ByteString.fromString("2"),
      ByteString.fromString("3")).iterator();
    final Source<ByteString, NotUsed> dataBytes = Source.fromIterator(() -> iterator);
    testRoute(route).run(
      HttpRequest.POST("/")
        .withEntity(HttpEntities.create(ContentTypes.TEXT_PLAIN_UTF8, dataBytes))
    ).assertEntity("123");
    //#extractStrictEntity
  }

  @Test
  public void testToStrictEntity() {
    //#toStrictEntity
    final FiniteDuration timeout = FiniteDuration.create(3, TimeUnit.SECONDS);
    final Route route = toStrictEntity(timeout, () ->
      extractRequest(req -> {
        if (req.entity() instanceof HttpEntity.Strict) {
          final HttpEntity.Strict strict = (HttpEntity.Strict)req.entity();
          return complete("Request entity is strict, data=" + strict.getData().utf8String());
        } else {
          return complete("Ooops, request entity is not strict!");
        }
      })
    );

    // tests:
    final Iterator iterator = Arrays.asList(
      ByteString.fromString("1"),
      ByteString.fromString("2"),
      ByteString.fromString("3")).iterator();
    final Source<ByteString, NotUsed> dataBytes = Source.fromIterator(() -> iterator);
    testRoute(route).run(
      HttpRequest.POST("/")
        .withEntity(HttpEntities.create(ContentTypes.TEXT_PLAIN_UTF8, dataBytes))
    ).assertEntity("Request entity is strict, data=123");
    //#toStrictEntity
  }

  @Test
  public void testExtractActorSystem() {
    //#extractActorSystem
    final Route route = extractActorSystem(actorSystem ->
      complete("Actor System extracted, hash=" + actorSystem.hashCode())
    );

    // tests:
    testRoute(route).run(HttpRequest.GET("/"))
      .assertEntity("Actor System extracted, hash=" + system().hashCode());
    //#extractActorSystem
  }

}
