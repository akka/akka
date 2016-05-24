/*
 * Copyright (C) 2015-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package docs.http.javadsl.server.directives;

import akka.http.javadsl.model.HttpRequest;
import akka.http.javadsl.model.HttpResponse;
import akka.http.javadsl.server.Route;
import akka.http.javadsl.server.directives.FramedEntityStreamingDirectives;
import akka.http.javadsl.server.directives.LogEntry;
import akka.http.javadsl.testkit.JUnitRouteTest;
import akka.http.scaladsl.server.Rejection;
import org.junit.Test;

import java.util.List;
import java.util.Optional;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.stream.Collectors;

import static akka.event.Logging.InfoLevel;

import static akka.http.javadsl.server.directives.FramedEntityStreamingDirectives.*;

public class FramedEntityStreamingExamplesTest extends JUnitRouteTest {

  @Test
  public void testRenderSource() {
    FramedEntityStreamingDirectives.
  }

}
