/*
 * Copyright (C) 2016 Lightbend Inc. <http://www.lightbend.com>
 */

package docs.http.javadsl.server;

import akka.NotUsed;
import akka.http.javadsl.common.FramingWithContentType;
import akka.http.javadsl.common.JsonSourceRenderingModes;
import akka.http.javadsl.marshallers.jackson.Jackson;
import akka.http.javadsl.model.*;
import akka.http.javadsl.model.headers.Accept;
import akka.http.javadsl.server.*;
import akka.http.javadsl.testkit.JUnitRouteTest;
import akka.http.javadsl.testkit.TestRoute;
import akka.stream.javadsl.Source;
import akka.util.ByteString;
import docs.http.javadsl.server.testkit.MyAppService;
import org.junit.Test;

import java.util.concurrent.CompletionStage;

public class JsonStreamingExamplesTest extends JUnitRouteTest {

  //#routes
  final Route tweets() {
    //#formats
    final Unmarshaller<ByteString, JavaTweet> JavaTweets = Jackson.byteStringUnmarshaller(JavaTweet.class);
    //#formats

    //#response-streaming
    final Route responseStreaming = path("tweets", () ->
      get(() ->
        parameter(StringUnmarshallers.INTEGER, "n", n -> {
          final Source<JavaTweet, NotUsed> tws =
            Source.repeat(new JavaTweet("Hello World!")).take(n);
          return completeOKWithSource(tws, Jackson.marshaller(), JsonSourceRenderingModes.arrayCompact());
        })
      )
    );
    //#response-streaming

    //#incoming-request-streaming
    final Route incomingStreaming = path("tweets", () ->
      post(() ->
        extractMaterializer(mat -> {
            final FramingWithContentType jsonFraming = EntityStreamingSupport.bracketCountingJsonFraming(128);

            return entityasSourceOf(JavaTweets, jsonFraming, sourceOfTweets -> {
              final CompletionStage<Integer> tweetsCount = sourceOfTweets.runFold(0, (acc, tweet) -> acc + 1, mat);
              return onComplete(tweetsCount, c -> complete("Total number of tweets: " + c));
            });
          }
        )
      )  
    );
    //#incoming-request-streaming
    
    return responseStreaming.orElse(incomingStreaming);
  }
  //#routes
  
  @Test
  public void getTweetsTest() {
    //#response-streaming
    // tests:
    final TestRoute routes = testRoute(tweets());
    
    // test happy path
    final Accept acceptApplication = Accept.create(MediaRanges.create(MediaTypes.APPLICATION_JSON));
    routes.run(HttpRequest.GET("/tweets?n=2").addHeader(acceptApplication))
      .assertStatusCode(200)
      .assertEntity("[{\"message\":\"Hello World!\"},{\"message\":\"Hello World!\"}]");

    // test responses to potential errors
    final Accept acceptText = Accept.create(MediaRanges.ALL_TEXT);
    routes.run(HttpRequest.GET("/tweets?n=3").addHeader(acceptText))
      .assertStatusCode(StatusCodes.NOT_ACCEPTABLE) // 406
      .assertEntity("Resource representation is only available with these types:\napplication/json");
    //#response-streaming
  }

  //#models
  private static final class JavaTweet {
    private String message;

    public JavaTweet(String message) {
      this.message = message;
    }

    public void setMessage(String message) {
      this.message = message;
    }

    public String getMessage() {
      return message;
    }

  }
  //#models
}
