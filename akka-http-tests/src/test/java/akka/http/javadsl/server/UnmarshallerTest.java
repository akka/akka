/*
 * Copyright (C) 2009-2016 Typesafe Inc. <http://www.typesafe.com>
 */
package akka.http.javadsl.server;

import akka.http.javadsl.model.*;
import akka.http.javadsl.testkit.JUnitRouteTest;
import org.junit.Test;

import java.util.Arrays;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.assertEquals;

public class UnmarshallerTest extends JUnitRouteTest {

  @Test
  public void canChooseOneOfManyUnmarshallers() throws Exception {
    Unmarshaller<HttpEntity, String> jsonUnmarshaller =
      Unmarshaller.forMediaType(MediaTypes.APPLICATION_JSON, Unmarshaller.entityToString()).thenApply((str) -> "json");
    Unmarshaller<HttpEntity, String> xmlUnmarshaller =
      Unmarshaller.forMediaType(MediaTypes.TEXT_XML, Unmarshaller.entityToString()).thenApply((str) -> "xml");

    final Unmarshaller<HttpEntity, String> both = Unmarshaller.firstOf(jsonUnmarshaller, xmlUnmarshaller);

    {
      CompletionStage<String> resultStage =
        both.unmarshall(
          HttpEntities.create(ContentTypes.TEXT_XML_UTF8, "<suchXml/>"),
          system().dispatcher(),
          materializer());

      assertEquals("xml", resultStage.toCompletableFuture().get(3, TimeUnit.SECONDS));
    }


    {
      CompletionStage<String> resultStage =
        both.unmarshall(
          HttpEntities.create(ContentTypes.APPLICATION_JSON, "{}"),
          system().dispatcher(),
          materializer());

      assertEquals("json", resultStage.toCompletableFuture().get(3, TimeUnit.SECONDS));
    }
  }

  @Test
  public void oneMarshallerCanHaveMultipleMediaTypes() throws Exception {
    Unmarshaller<HttpEntity, String> xmlUnmarshaller =
      Unmarshaller.forMediaTypes(
        Arrays.asList(MediaTypes.APPLICATION_XML, MediaTypes.TEXT_XML),
        Unmarshaller.entityToString()).thenApply((str) -> "xml");

    {
      CompletionStage<String> resultStage =
        xmlUnmarshaller.unmarshall(
          HttpEntities.create(ContentTypes.TEXT_XML_UTF8, "<suchXml/>"),
          system().dispatcher(),
          materializer());

      assertEquals("xml", resultStage.toCompletableFuture().get(3, TimeUnit.SECONDS));
    }

    {
      CompletionStage<String> resultStage =
        xmlUnmarshaller.unmarshall(
          HttpEntities.create(ContentTypes.create(MediaTypes.APPLICATION_XML, HttpCharsets.UTF_8), "<suchXml/>"),
          system().dispatcher(),
          materializer());

      assertEquals("xml", resultStage.toCompletableFuture().get(3, TimeUnit.SECONDS));
    }
  }
}
