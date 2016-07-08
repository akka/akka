/**
 * Copyright (C) 2016-2016 Lightbend Inc. <http://www.lightbend.com>
 */
package docs.http.javadsl.server.directives;

import akka.http.impl.engine.rendering.BodyPartRenderer;
import akka.http.javadsl.model.*;
import akka.http.javadsl.server.Route;
import akka.http.javadsl.server.Unmarshaller;
import akka.http.javadsl.server.directives.FileInfo;
import akka.http.javadsl.testkit.JUnitRouteTest;
import akka.stream.javadsl.Framing;
import akka.stream.javadsl.Source;
import akka.util.ByteString;
import org.junit.Ignore;
import org.junit.Test;
import scala.concurrent.duration.Duration;
import scala.concurrent.duration.FiniteDuration;

import java.io.File;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.function.BiFunction;

public class FileUploadDirectivesExamplesTest extends JUnitRouteTest {

  @Test
  public void testUploadedFile() {
    //#uploadedFile
    // function (FileInfo, File) => Route to process the file metadata and file itself
    BiFunction<FileInfo, File, Route> infoFileRoute =
      (info, file) -> {
        // do something with the file and file metadata ...
        file.delete();
        return complete(StatusCodes.OK);
      };


    final Route route = uploadedFile("csv", infoFileRoute);

    Map<String, String> filenameMapping = new HashMap<>();
    filenameMapping.put("filename", "data.csv");

    akka.http.javadsl.model.Multipart.FormData multipartForm =
      Multiparts.createStrictFormDataFromParts(Multiparts.createFormDataBodyPartStrict("csv",
        HttpEntities.create(ContentTypes.TEXT_PLAIN_UTF8,
          "1,5,7\n11,13,17"), filenameMapping));

    // test:
    testRoute(route).run(HttpRequest.POST("/")
      .withEntity(
        multipartForm.toEntity(HttpCharsets.UTF_8,
          BodyPartRenderer
            .randomBoundaryWithDefaults())))
      .assertStatusCode(StatusCodes.OK);
    //#
  }

  @Test
  public void testFileUpload() {
    //#fileUpload
    final Route route = extractRequestContext(ctx -> {
      // function (FileInfo, Source<ByteString,Object>) => Route to process the file contents
      BiFunction<FileInfo, Source<ByteString, Object>, Route> processUploadedFile =
        (metadata, byteSource) -> {
          CompletionStage<Integer> sumF = byteSource.via(Framing.delimiter(
            ByteString.fromString("\n"), 1024))
            .mapConcat(bs -> Arrays.asList(bs.utf8String().split(",")))
            .map(s -> Integer.parseInt(s))
            .runFold(0, (acc, n) -> acc + n, ctx.getMaterializer());
          return onSuccess(() -> sumF, sum -> complete("Sum: " + sum));
        };
      return fileUpload("csv", processUploadedFile);
    });

    Map<String, String> filenameMapping = new HashMap<>();
    filenameMapping.put("filename", "primes.csv");

    akka.http.javadsl.model.Multipart.FormData multipartForm =
      Multiparts.createStrictFormDataFromParts(
        Multiparts.createFormDataBodyPartStrict("csv",
          HttpEntities.create(ContentTypes.TEXT_PLAIN_UTF8,
            "2,3,5\n7,11,13,17,23\n29,31,37\n"), filenameMapping));

    // test:
    testRoute(route).run(HttpRequest.POST("/").withEntity(
      multipartForm.toEntity(HttpCharsets.UTF_8, BodyPartRenderer.randomBoundaryWithDefaults())))
      .assertStatusCode(StatusCodes.OK).assertEntityAs(Unmarshaller.entityToString(), "Sum: 178");
    //#
  }

  @Ignore("compileOnly")
  @Test
  public void testFileProcessing() {
    //#fileProcessing
    final Route route = extractRequestContext(ctx -> {
      // function (FileInfo, Source<ByteString,Object>) => Route to process the file contents
      BiFunction<FileInfo, Source<ByteString, Object>, Route> processUploadedFile =
        (metadata, byteSource) -> {
          CompletionStage<Integer> sumF = byteSource.via(Framing.delimiter(
            ByteString.fromString("\n"), 1024))
            .mapConcat(bs -> Arrays.asList(bs.utf8String().split(",")))
            .map(s -> Integer.parseInt(s))
            .runFold(0, (acc, n) -> acc + n, ctx.getMaterializer());
          return onSuccess(() -> sumF, sum -> complete("Sum: " + sum));
        };
      return fileUpload("csv", processUploadedFile);
    });

    Map<String, String> filenameMapping = new HashMap<>();
    filenameMapping.put("filename", "primes.csv");

    String prefix = "primes";
    String suffix = ".csv";

    File tempFile = null;
    try {
      tempFile = File.createTempFile(prefix, suffix);
      tempFile.deleteOnExit();
      Files.write(tempFile.toPath(), Arrays.asList("2,3,5", "7,11,13,17,23", "29,31,37"), Charset.forName("UTF-8"));
    } catch (Exception e) {
      // ignore
    }


    akka.http.javadsl.model.Multipart.FormData multipartForm =
      Multiparts.createFormDataFromPath("csv", ContentTypes.TEXT_PLAIN_UTF8, tempFile.toPath());

    // test:
    testRoute(route).run(HttpRequest.POST("/").withEntity(
      multipartForm.toEntity(HttpCharsets.UTF_8, BodyPartRenderer.randomBoundaryWithDefaults())))
      .assertStatusCode(StatusCodes.OK).assertEntityAs(Unmarshaller.entityToString(), "Sum: 178");
    //#
  }
}
