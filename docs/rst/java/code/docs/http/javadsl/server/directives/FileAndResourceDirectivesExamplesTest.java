/*
 * Copyright (C) 2016-2016 Lightbend Inc. <http://www.lightbend.com>
 */
package docs.http.javadsl.server.directives;

import akka.http.javadsl.model.HttpRequest;
import akka.http.javadsl.model.StatusCodes;
import akka.http.javadsl.server.PathMatchers;
import akka.http.javadsl.server.Route;
import akka.http.javadsl.server.directives.DirectoryRenderer;
import akka.http.javadsl.testkit.JUnitRouteTest;
import org.junit.Ignore;
import org.junit.Test;
import scala.NotImplementedError;

import static akka.http.javadsl.server.PathMatchers.segment;

public class FileAndResourceDirectivesExamplesTest extends JUnitRouteTest {

  @Ignore("Compile only test")
  @Test
  public void testGetFromFile() {
    //#getFromFile
    final Route route = path(PathMatchers.segment("logs").slash(segment()), name ->
      getFromFile(name + ".log")
    );

    // tests:
    testRoute(route).run(HttpRequest.GET("/logs/example"))
      .assertEntity("example file contents");
    //#getFromFile
  }

  @Ignore("Compile only test")
  @Test
  public void testGetFromResource() {
    //#getFromResource
    final Route route = path(PathMatchers.segment("logs").slash(segment()), name ->
      getFromResource(name + ".log")
    );

    // tests:
    testRoute(route).run(HttpRequest.GET("/logs/example"))
      .assertEntity("example file contents");
    //#getFromResource
  }

  @Ignore("Compile only test")
  @Test
  public void testListDirectoryContents() {
    //#listDirectoryContents
    final Route route = route(
      path("tmp", () -> listDirectoryContents("/tmp")),
      path("custom", () -> {
        // implement your custom renderer here
        final DirectoryRenderer renderer = renderVanityFooter -> {
          throw new NotImplementedError();
        };
        return listDirectoryContents(renderer, "/tmp");
      })
    );

    // tests:
    testRoute(route).run(HttpRequest.GET("/logs/example"))
      .assertEntity("example file contents");
    //#listDirectoryContents
  }

  @Ignore("Compile only test")
  @Test
  public void testGetFromBrowseableDirectory() {
    //#getFromBrowseableDirectory
    final Route route = path("tmp", () ->
      getFromBrowseableDirectory("/tmp")
    );

    // tests:
    testRoute(route).run(HttpRequest.GET("/tmp"))
      .assertStatusCode(StatusCodes.OK);
    //#getFromBrowseableDirectory
  }

  @Ignore("Compile only test")
  @Test
  public void testGetFromBrowseableDirectories() {
    //#getFromBrowseableDirectories
    final Route route = path("tmp", () ->
      getFromBrowseableDirectories("/main", "/backups")
    );

    // tests:
    testRoute(route).run(HttpRequest.GET("/tmp"))
      .assertStatusCode(StatusCodes.OK);
    //#getFromBrowseableDirectories
  }

  @Ignore("Compile only test")
  @Test
  public void testGetFromDirectory() {
    //#getFromDirectory
    final Route route = pathPrefix("tmp", () ->
      getFromDirectory("/tmp")
    );

    // tests:
    testRoute(route).run(HttpRequest.GET("/tmp/example"))
      .assertEntity("example file contents");
    //#getFromDirectory
  }

  @Ignore("Compile only test")
  @Test
  public void testGetFromResourceDirectory() {
    //#getFromResourceDirectory
    final Route route = pathPrefix("examples", () ->
      getFromResourceDirectory("/examples")
    );

    // tests:
    testRoute(route).run(HttpRequest.GET("/examples/example-1"))
      .assertEntity("example file contents");
    //#getFromResourceDirectory
  }
}
