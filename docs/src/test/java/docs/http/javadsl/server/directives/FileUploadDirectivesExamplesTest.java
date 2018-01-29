/**
 * Copyright (C) 2016-2017 Lightbend Inc. <http://www.lightbend.com>
 */
package docs.http.javadsl.server.directives;

import akka.http.impl.engine.rendering.BodyPartRenderer;
import akka.http.javadsl.model.*;
import akka.http.javadsl.server.Route;
import akka.http.javadsl.unmarshalling.Unmarshaller;
import akka.http.javadsl.server.directives.FileInfo;
import akka.http.javadsl.testkit.JUnitRouteTest;
import akka.stream.javadsl.Framing;
import akka.stream.javadsl.Source;
import akka.util.ByteString;
import org.junit.Ignore;
import org.junit.Test;

import java.io.File;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.BiFunction;
import java.util.function.Function;

import static scala.compat.java8.JFunction.func;

public class FileUploadDirectivesExamplesTest extends JUnitRouteTest {

  @Test
  public void testUploadedFile() {
    //#uploadedFile
    final Route route = uploadedFile("csv", (info, file) -> {
      // do something with the file and file metadata ...
      file.delete();
      return complete(StatusCodes.OK);
    });

    Map<String, String> filenameMapping = new HashMap<>();
    filenameMapping.put("filename", "primes.csv");

    akka.http.javadsl.model.Multipart.FormData multipartForm =
      Multiparts.createStrictFormDataFromParts(Multiparts.createFormDataBodyPartStrict("csv",
        HttpEntities.create(ContentTypes.TEXT_PLAIN_UTF8,
          "2,3,5\n7,11,13,17,23\n29,31,37\n"), filenameMapping));

    // test:
    testRoute(route).run(HttpRequest.POST("/")
      .withEntity(
        multipartForm.toEntity(BodyPartRenderer
		  .randomBoundaryWithDefaults())))
      .assertStatusCode(StatusCodes.OK);
    //#uploadedFile
  }

  @Test
  public void testStoreUploadedFile() {
    //#storeUploadedFile
    final Function<FileInfo, File> temporaryDestination = (info) -> {
      try {
        return File.createTempFile(info.getFileName(), ".tmp");
      } catch (Exception e) {
        return null;
      }
    };

    final Route route = storeUploadedFile("csv", temporaryDestination, (info, file) -> {
      // do something with the file and file metadata ...
      file.delete();
      return complete(StatusCodes.OK);
    });

    Map<String, String> filenameMapping = new HashMap<>();
    filenameMapping.put("filename", "primes.csv");

    akka.http.javadsl.model.Multipart.FormData multipartForm =
      Multiparts.createStrictFormDataFromParts(Multiparts.createFormDataBodyPartStrict("csv",
        HttpEntities.create(ContentTypes.TEXT_PLAIN_UTF8,
          "2,3,5\n7,11,13,17,23\n29,31,37\n"), filenameMapping));

    // test:
    testRoute(route).run(HttpRequest.POST("/")
      .withEntity(
        multipartForm.toEntity(BodyPartRenderer
		  .randomBoundaryWithDefaults())))
      .assertStatusCode(StatusCodes.OK);
    //#storeUploadedFile
  }

  @Test
  public void testStoreUploadedFiles() {
    //#storeUploadedFiles
    final Function<FileInfo, File> temporaryDestination = info -> {
      try {
        return File.createTempFile(info.getFileName(), ".tmp");
      } catch (Exception e) {
        return null;
      }
    };

    final Route route = storeUploadedFiles("csv", temporaryDestination, files -> {
      files.forEach(item -> {
        // do something with the file and file metadata ...
        FileInfo info = item.getKey();
        File file = item.getValue();
        file.delete();
      });
      return complete(StatusCodes.OK);
    });

    Map<String, String> filenameMappingA = new HashMap<>();
    Map<String, String> filenameMappingB = new HashMap<>();
    filenameMappingA.put("filename", "primesA.csv");
    filenameMappingB.put("filename", "primesB.csv");

    akka.http.javadsl.model.Multipart.FormData multipartForm =
      Multiparts.createStrictFormDataFromParts(
        Multiparts.createFormDataBodyPartStrict("csv",
          HttpEntities.create(ContentTypes.TEXT_PLAIN_UTF8,
            "2,3,5\n7,11,13,17,23\n29,31,37\n"), filenameMappingA),
        Multiparts.createFormDataBodyPartStrict("csv",
          HttpEntities.create(ContentTypes.TEXT_PLAIN_UTF8,
            "41,43,47\n53,59,61,67,71\n73,79,83\n"), filenameMappingB));

    // test:
    testRoute(route).run(HttpRequest.POST("/")
      .withEntity(
        multipartForm.toEntity(BodyPartRenderer
		  .randomBoundaryWithDefaults())))
      .assertStatusCode(StatusCodes.OK);
    //#storeUploadedFiles
  }

  @Test
  public void testFileUpload() {
    //#fileUpload
    final Route route = extractRequestContext(ctx -> {
      return fileUpload("csv", (metadata, byteSource) -> {
        // sum the numbers as they arrive
        CompletionStage<Integer> sumF = byteSource.via(Framing.delimiter(
          ByteString.fromString("\n"), 1024))
            .mapConcat(bs -> Arrays.asList(bs.utf8String().split(",")))
            .map(s -> Integer.parseInt(s))
            .runFold(0, (acc, n) -> acc + n, ctx.getMaterializer());
        return onSuccess(sumF, sum -> complete("Sum: " + sum));
      });
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
      multipartForm.toEntity(BodyPartRenderer.randomBoundaryWithDefaults())))
      .assertStatusCode(StatusCodes.OK)
      .assertEntityAs(Unmarshaller.entityToString(), "Sum: 178");
    //#fileUpload
  }

  @Test
  public void testFileUploadAll() {
    //#fileUploadAll
    final Route route = extractRequestContext(ctx -> {
      return fileUploadAll("csv", byteSources -> {
        // accumulate the sum of each file
        CompletionStage<Integer> sumF = byteSources.stream()
          .map(item -> {
            // sum the numbers as they arrive
            return item.getValue().via(Framing.delimiter(
              ByteString.fromString("\n"), 1024))
                .mapConcat(bs -> Arrays.asList(bs.utf8String().split(",")))
                .map(s -> Integer.parseInt(s))
                .runFold(0, (acc, n) -> acc + n, ctx.getMaterializer());
          })
          .reduce(CompletableFuture.completedFuture(0), (accF, intF) -> {
            return accF.thenCombine(intF, (a, b) -> a + b);
          });

        return onSuccess(sumF, sum -> complete("Sum: " + sum));
      });
    });

    Map<String, String> filenameMappingA = new HashMap<>();
    Map<String, String> filenameMappingB = new HashMap<>();
    filenameMappingA.put("filename", "primesA.csv");
    filenameMappingB.put("filename", "primesB.csv");

    akka.http.javadsl.model.Multipart.FormData multipartForm =
      Multiparts.createStrictFormDataFromParts(
        Multiparts.createFormDataBodyPartStrict("csv",
          HttpEntities.create(ContentTypes.TEXT_PLAIN_UTF8,
            "2,3,5\n7,11,13,17,23\n29,31,37\n"), filenameMappingA),
        Multiparts.createFormDataBodyPartStrict("csv",
          HttpEntities.create(ContentTypes.TEXT_PLAIN_UTF8,
            "41,43,47\n53,59,61,67,71\n73,79,83\n"), filenameMappingB));

    // test:
    testRoute(route).run(HttpRequest.POST("/").withEntity(
      multipartForm.toEntity(BodyPartRenderer.randomBoundaryWithDefaults())))
      .assertStatusCode(StatusCodes.OK)
      .assertEntityAs(Unmarshaller.entityToString(), "Sum: 855");
    //#fileUploadAll
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
          return onSuccess(sumF, sum -> complete("Sum: " + sum));
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
      multipartForm.toEntity(BodyPartRenderer.randomBoundaryWithDefaults())))
      .assertStatusCode(StatusCodes.OK).assertEntityAs(Unmarshaller.entityToString(), "Sum: 178");
    //#fileProcessing
  }
}
