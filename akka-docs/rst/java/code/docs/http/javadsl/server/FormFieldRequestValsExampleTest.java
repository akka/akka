/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package docs.http.javadsl.server;


import org.junit.Test;

import akka.http.javadsl.model.FormData;
import akka.http.javadsl.model.HttpRequest;
import akka.http.javadsl.server.Route;
import akka.http.javadsl.server.StringUnmarshallers;
import akka.http.javadsl.server.StringUnmarshaller;
import akka.http.javadsl.server.Unmarshaller;
import akka.http.javadsl.testkit.JUnitRouteTest;
import akka.japi.Pair;

public class FormFieldRequestValsExampleTest extends JUnitRouteTest {

  @Test
  public void testFormFieldVals() {
    //#simple

    final Route route =
      formField("name", n ->
        formField(StringUnmarshallers.INTEGER, "age", a ->
          complete(String.format("Name: %s, age: %d", n, a))
        )
      );

    // tests:
    final FormData formData = FormData.create(
      Pair.create("name", "Blippy"),
      Pair.create("age", "42"));
    final HttpRequest request =
      HttpRequest
        .POST("/")
        .withEntity(formData.toEntity());
    testRoute(route).run(request).assertEntity("Name: Blippy, age: 42");

    //#simple
  }

  @Test
  public void testFormFieldValsUnmarshaling() {
    //#custom-unmarshal
    Unmarshaller<String, SampleId> SAMPLE_ID = StringUnmarshaller.sync(s -> new SampleId(Integer.valueOf(s)));

    final Route route =
      formField(SAMPLE_ID, "id", sid ->
        complete(String.format("SampleId: %s", sid.id))
      );

    // tests:
    final FormData formData = FormData.create(Pair.create("id", "1337"));
    final HttpRequest request =
      HttpRequest
        .POST("/")
        .withEntity(formData.toEntity());
    testRoute(route).run(request).assertEntity("SampleId: 1337");

    //#custom-unmarshal
  }

  static class SampleId {
    public final int id;

    SampleId(int id) {
      this.id = id;
    }
  }


}