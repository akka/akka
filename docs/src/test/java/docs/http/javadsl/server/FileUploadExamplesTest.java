/**
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */
package docs.http.javadsl.server;

import akka.Done;
import akka.NotUsed;
import akka.actor.ActorRef;
import akka.http.javadsl.server.Route;
import akka.http.javadsl.testkit.JUnitRouteTest;
import akka.http.javadsl.unmarshalling.Unmarshaller;
import akka.japi.Pair;
import akka.stream.Materializer;
import akka.stream.javadsl.FileIO;
import akka.stream.javadsl.Flow;
import akka.stream.javadsl.Framing;
import akka.stream.javadsl.Sink;
import akka.util.ByteString;
import org.junit.Test;

import java.io.File;
import java.io.Serializable;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

import static akka.http.javadsl.server.PathMatchers.longSegment;
import static akka.http.javadsl.server.PathMatchers.segment;

public class FileUploadExamplesTest extends JUnitRouteTest {

  @Test
  public void compileOnlySpec() throws Exception {
    // just making sure for it to be really compiled / run even if empty
  }

  //#simple-upload
  Route uploadVideo() {
    //#simple-upload
    final Materializer materializer = materializer();
    final ActorRef metadataActor = system().deadLetters();
    //#simple-upload
    return path("video", () ->
      entity(Unmarshaller.entityToMultipartFormData(), formData -> {
        // collect all parts of the multipart as it arrives into a map
        final CompletionStage<Map<String, Object>> allParts =
          formData.getParts().mapAsync(1, bodyPart -> {
            if ("file".equals(bodyPart.getName())) {
              // stream into a file as the chunks of it arrives and return a CompletionStage
              // file to where it got stored
              final File file = File.createTempFile("upload", "tmp");
              return bodyPart.getEntity().getDataBytes()
                .runWith(FileIO.toPath(file.toPath()), materializer)
                .thenApply(ignore ->
                  new Pair<String, Object>(bodyPart.getName(), file)
                );
            } else {
              // collect form field values
              return bodyPart.toStrict(2 * 1000, materializer)
                .thenApply(strict ->
                  new Pair<String, Object>(bodyPart.getName(),
                    strict.getEntity().getData().utf8String())
                );
            }
          }).runFold(new HashMap<String, Object>(), (acc, pair) -> {
            acc.put(pair.first(), pair.second());
            return acc;
          }, materializer);

        // simulate a DB call
        final CompletionStage<Void> done = allParts.thenCompose(map ->
          // You would have some better validation/unmarshalling here
          DB.create((File) map.get("file"),
            (String) map.get("title"),
            (String) map.get("author")
          ));

        // when processing have finished create a response for the user
        return onSuccess(() -> allParts, x -> complete("ok!"));
      })
    );

  }

  static class DB {
    static CompletionStage<Void> create(final File file, final String title, final String author) {
      return CompletableFuture.completedFuture(null);
    }
  }

  //#stream-csv-upload
  Route csvUploads() {
    //#stream-csv-upload
    final Materializer materializer = materializer();
    final ActorRef metadataActor = system().deadLetters();
    //#stream-csv-upload
    final Flow<ByteString, ByteString, NotUsed> splitLines =
      Framing.delimiter(ByteString.fromString("\n"), 256);

    return path(segment("metadata").slash(longSegment()), id ->
      entity(Unmarshaller.entityToMultipartFormData(), formData -> {

        final CompletionStage<Done> done = formData.getParts().mapAsync(1, bodyPart ->
          bodyPart.getFilename().filter(name -> name.endsWith(".csv")).map(ignored ->
            bodyPart.getEntity().getDataBytes()
              .via(splitLines)
              .map(bs -> bs.utf8String().split(","))
              .runForeach(csv ->
                  metadataActor.tell(new Entry(id, csv), ActorRef.noSender()),
                materializer)
          ).orElseGet(() ->
            // in case the uploaded file is not a CSV
            CompletableFuture.completedFuture(Done.getInstance()))
        ).runWith(Sink.ignore(), materializer);

        // when processing have finished create a response for the user
        return onComplete(() -> done, ignored -> complete("ok!"));
      })
    );
  }
  //#stream-csv-upload

  static class Entry implements Serializable {
    final Long id;
    final String[] values;

    Entry(Long id, String[] values) {
      this.id = id;
      this.values = values;
    }

    @Override
    public String toString() {
      return "Entry{" +
        "id=" + id +
        ", values=" + Arrays.toString(values) +
        '}';
    }
  }

}