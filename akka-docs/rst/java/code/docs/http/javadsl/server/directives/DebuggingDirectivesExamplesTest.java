/*
 * Copyright (C) 2015-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package docs.http.javadsl.server.directives;

import java.util.Arrays;
import java.util.regex.Pattern;

import org.junit.Test;

import akka.http.javadsl.model.HttpRequest;
import akka.http.javadsl.model.HttpResponse;
import akka.http.javadsl.model.StatusCodes;
import akka.http.javadsl.model.headers.Host;
import akka.http.javadsl.server.Route;
import akka.http.javadsl.server.RequestContext;
import akka.http.javadsl.testkit.JUnitRouteTest;
import java.util.function.Function;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import akka.http.javadsl.model.Uri;
import akka.http.javadsl.model.headers.Location;
import akka.http.javadsl.server.directives.DebuggingDirectives;
import akka.http.javadsl.server.directives.RouteDirectives;
import akka.event.Logging;
import akka.event.Logging.LogLevel;
import akka.http.javadsl.server.directives.LogEntry;
import java.util.List;
import akka.http.scaladsl.server.Rejection;

import static akka.event.Logging.InfoLevel;
import java.util.stream.Collectors;
import java.util.Optional;

public class DebuggingDirectivesExamplesTest extends JUnitRouteTest {

  @Test
  public void testLogRequest() {
    //#logRequest
    // logs request with "get-user"
    final Route routeBasicLogRequest = get(() -> 
      logRequest("get-user", () -> complete("logged")));
    
    // logs request with "get-user" as Info
    final Route routeBasicLogRequestAsInfo = get(() -> 
      logRequest("get-user", InfoLevel(), () -> complete("logged")));

    // logs just the request method at info level
    Function<HttpRequest, LogEntry> requestMethodAsInfo = (request) -> 
      LogEntry.create(request.method().toString(), InfoLevel());

    final Route routeUsingFunction = get(() -> 
      logRequest(requestMethodAsInfo, () -> complete("logged")));

    // tests:
    testRoute(routeBasicLogRequest).run(HttpRequest.GET("/"))
      .assertEntity("logged");
    //#logRequest
  }

  @Test
  public void testLogRequestResult() {
    //#logRequestResult
    // using logRequestResult

    // handle request to optionally generate a log entry
    BiFunction<HttpRequest, HttpResponse, Optional<LogEntry>> requestMethodAsInfo = 
      (request, response) ->
        (response.status().isSuccess())  ? 
            Optional.of(
              LogEntry.create(
                request.method().toString() + ":" + response.status().intValue(), 
                InfoLevel()))
          : Optional.empty(); // not a successful response

    // handle rejections to optionally generate a log entry
    BiFunction<HttpRequest, List<Rejection>, Optional<LogEntry>> rejectionsAsInfo = 
      (request, rejections) ->
        (!rejections.isEmpty())  ? 
          Optional.of(
            LogEntry.create(
                rejections
                .stream()
                .map(Rejection::toString)
                .collect(Collectors.joining(", ")), 
              InfoLevel()))
          : Optional.empty(); // no rejections

    final Route route = get(() -> logRequestResultOptional(
      requestMethodAsInfo, 
      rejectionsAsInfo,
      () -> complete("logged")));
    // tests:
    testRoute(route).run(HttpRequest.GET("/")).assertEntity("logged");
    //#logRequestResult
  }

  @Test
  public void testLogResult() {
    //#logResult
    // logs result with "get-user"
    final Route routeBasicLogResult = get(() ->
      logResult("get-user", () -> complete("logged")));

    // logs result with "get-user" as Info
    final Route routeBasicLogResultAsInfo = get(() ->
      logResult("get-user", InfoLevel(), () -> complete("logged")));

    // logs the result and the rejections as LogEntry
    Function<HttpResponse, LogEntry> showSuccessAsInfo = (response) ->
      LogEntry.create(String.format("Response code '%d'", response.status().intValue()), 
        InfoLevel());

    Function<List<Rejection>, LogEntry> showRejectionAsInfo = (rejections) ->
      LogEntry.create(
        rejections
        .stream()
        .map(rejection->rejection.toString())
        .collect(Collectors.joining(", ")), 
      InfoLevel());

    final Route routeUsingFunction = get(() ->
      logResult(showSuccessAsInfo, showRejectionAsInfo, () -> complete("logged")));
    // tests:
    testRoute(routeBasicLogResult).run(HttpRequest.GET("/"))
      .assertEntity("logged");
    //#logResult
  }

}