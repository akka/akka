/*
 * Copyright (C) 2016 Lightbend Inc. <http://www.lightbend.com>
 */

package docs.http.javadsl.server;

import akka.NotUsed;
import akka.http.javadsl.common.CsvEntityStreamingSupport;
import akka.http.javadsl.common.JsonEntityStreamingSupport;
import akka.http.javadsl.marshallers.jackson.Jackson;
import akka.http.javadsl.marshalling.Marshaller;
import akka.http.javadsl.model.*;
import akka.http.javadsl.model.headers.Accept;
import akka.http.javadsl.server.*;
import akka.http.javadsl.testkit.JUnitRouteTest;
import akka.http.javadsl.testkit.TestRoute;
import akka.http.javadsl.unmarshalling.StringUnmarshallers;
import akka.http.javadsl.common.EntityStreamingSupport;
import akka.http.javadsl.unmarshalling.Unmarshaller;
import akka.stream.javadsl.Flow;
import akka.stream.javadsl.Source;
import akka.util.ByteString;
import org.junit.Test;

import java.util.concurrent.CompletionStage;

public class JsonStreamingExamplesTest extends JUnitRouteTest {

  //#routes
  final Route tweets() {
    //#formats
    final Unmarshaller<ByteString, JavaTweet> JavaTweets = Jackson.byteStringUnmarshaller(JavaTweet.class);
    //#formats

    //#response-streaming
    
    // Step 1: Enable JSON streaming
    // we're not using this in the example, but it's the simplest way to start:
    // The default rendering is a JSON array: `[el, el, el , ...]`
    final JsonEntityStreamingSupport jsonStreaming = EntityStreamingSupport.json();

    // Step 1.1: Enable and customise how we'll render the JSON, as a compact array:
    final ByteString start = ByteString.fromString("[");
    final ByteString between = ByteString.fromString(",");
    final ByteString end = ByteString.fromString("]");
    final Flow<ByteString, ByteString, NotUsed> compactArrayRendering = 
      Flow.of(ByteString.class).intersperse(start, between, end);
    
    final JsonEntityStreamingSupport compactJsonSupport = EntityStreamingSupport.json()
      .withFramingRendererFlow(compactArrayRendering);


    // Step 2: implement the route
    final Route responseStreaming = path("tweets", () ->
      get(() ->
        parameter(StringUnmarshallers.INTEGER, "n", n -> {
          final Source<JavaTweet, NotUsed> tws =
            Source.repeat(new JavaTweet(12, "Hello World!")).take(n);
          
          // Step 3: call complete* with your source, marshaller, and stream rendering mode
          return completeOKWithSource(tws, Jackson.marshaller(), compactJsonSupport);
        })
      )
    );
    //#response-streaming

    //#incoming-request-streaming
    final Route incomingStreaming = path("tweets", () ->
      post(() ->
        extractMaterializer(mat -> {
          final JsonEntityStreamingSupport jsonSupport = EntityStreamingSupport.json();

          return entityAsSourceOf(JavaTweets, jsonSupport, sourceOfTweets -> {
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
  
  final Route csvTweets() {
    //#csv-example
    final Marshaller<JavaTweet, ByteString> renderAsCsv = 
      Marshaller.withFixedContentType(ContentTypes.TEXT_CSV_UTF8, t -> 
        ByteString.fromString(t.getId() + "," + t.getMessage())
      );

    final CsvEntityStreamingSupport compactJsonSupport = EntityStreamingSupport.csv();

    final Route responseStreaming = path("tweets", () ->
      get(() ->
        parameter(StringUnmarshallers.INTEGER, "n", n -> {
          final Source<JavaTweet, NotUsed> tws =
            Source.repeat(new JavaTweet(12, "Hello World!")).take(n);
          return completeWithSource(tws, renderAsCsv, compactJsonSupport);
        })
      )
    );
    //#csv-example
    
    return responseStreaming;
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
      .assertEntity("[{\"id\":12,\"message\":\"Hello World!\"},{\"id\":12,\"message\":\"Hello World!\"}]");

    // test responses to potential errors
    final Accept acceptText = Accept.create(MediaRanges.ALL_TEXT);
    routes.run(HttpRequest.GET("/tweets?n=3").addHeader(acceptText))
      .assertStatusCode(StatusCodes.NOT_ACCEPTABLE) // 406
      .assertEntity("Resource representation is only available with these types:\napplication/json");
    //#response-streaming
  }
  
  @Test
  public void csvExampleTweetsTest() {
    //#response-streaming
    // tests --------------------------------------------
    final TestRoute routes = testRoute(csvTweets());
    
    // test happy path
    final Accept acceptCsv = Accept.create(MediaRanges.create(MediaTypes.TEXT_CSV));
    routes.run(HttpRequest.GET("/tweets?n=2").addHeader(acceptCsv))
      .assertStatusCode(200)
      .assertEntity("12,Hello World!\n" +
        "12,Hello World!");

    // test responses to potential errors
    final Accept acceptText = Accept.create(MediaRanges.ALL_APPLICATION);
    routes.run(HttpRequest.GET("/tweets?n=3").addHeader(acceptText))
      .assertStatusCode(StatusCodes.NOT_ACCEPTABLE) // 406
      .assertEntity("Resource representation is only available with these types:\ntext/csv; charset=UTF-8");
    //#response-streaming
  }

  //#models
  private static final class JavaTweet {
    private int id; 
    private String message;

    public JavaTweet(int id, String message) {
      this.id = id;
      this.message = message;
    }

    public int getId() {
      return id;
    }

    public void setId(int id) {
      this.id = id;
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
