/*
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */

package docs.http.javadsl;

//#single-request-decoding-example
import akka.actor.ActorSystem;
import akka.http.javadsl.Http;
import akka.http.javadsl.coding.Coder;
import akka.http.javadsl.model.HttpRequest;
import akka.http.javadsl.model.HttpResponse;
import akka.http.scaladsl.model.headers.HttpEncodings;
import akka.stream.ActorMaterializer;
import akka.stream.Materializer;
import scala.concurrent.duration.FiniteDuration;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;

public class HttpClientDecodingExample {

  public static void main(String[] args) throws Exception {

    final ActorSystem system = ActorSystem.create();
    final Materializer materializer = ActorMaterializer.create(system);

    final List<HttpRequest> httpRequests = Arrays.asList(
      HttpRequest.create("https://httpbin.org/gzip"), // Content-Encoding: gzip in response
      HttpRequest.create("https://httpbin.org/deflate"), // Content-Encoding: deflate in response
      HttpRequest.create("https://httpbin.org/get") // no Content-Encoding in response
    );

    final Http http = Http.get(system);

    final Function<HttpResponse, CompletionStage<HttpResponse>> decodeResponse = response -> {
      // Pick the right coder
      final Coder coder;
      if (HttpEncodings.gzip().equals(response.encoding())) {
        coder = Coder.Gzip;
      } else if (HttpEncodings.deflate().equals(response.encoding())) {
        coder = Coder.Deflate;
      } else {
        coder = Coder.NoCoding;
      }

      // Decode the entity
      return response.entity()
        .toStrict(FiniteDuration.create(3, TimeUnit.SECONDS).toMillis(), materializer)
        .thenCompose(strict ->
          coder.decode(strict.getData(), materializer))
        .thenApply(bs ->
          response.withEntity(response.entity().getContentType(), bs)
        );
    };

    final List<CompletableFuture<HttpResponse>> futureResponses = httpRequests.stream()
      .map(req -> http.singleRequest(req, materializer)
        .thenCompose(decodeResponse))
      .map(CompletionStage::toCompletableFuture)
      .collect(Collectors.toList());

    for (CompletableFuture<HttpResponse> futureResponse : futureResponses) {
      final HttpResponse httpResponse = futureResponse.get();
      System.out.println("response is: " + httpResponse);
    }

    system.terminate();
  }
}
//#single-request-decoding-example
