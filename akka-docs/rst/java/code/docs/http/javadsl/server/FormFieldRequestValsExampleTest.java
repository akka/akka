/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package docs.http.javadsl.server;

import akka.http.javadsl.model.FormData;
import akka.http.javadsl.model.HttpRequest;
import akka.http.javadsl.server.Route;
import akka.http.javadsl.server.values.FormField;
import akka.http.javadsl.server.values.FormFields;
import akka.http.javadsl.testkit.JUnitRouteTest;
import akka.japi.Pair;
import org.junit.Test;

public class FormFieldRequestValsExampleTest extends JUnitRouteTest {

  @Test
  public void testFormFieldVals() {
    //#simple
    FormField<String> name = FormFields.stringValue("name");
    FormField<Integer> age = FormFields.intValue("age");

    final Route route =
      route(
        handleWith2(name, age, (ctx, n, a) ->
          ctx.complete(String.format("Name: %s, age: %d", n, a))
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
    FormField<SampleId> sampleId = FormFields.fromString("id", SampleId.class, s -> new SampleId(Integer.valueOf(s)));

    final Route route =
      route(
        handleWith1(sampleId, (ctx, sid) ->
          ctx.complete(String.format("SampleId: %s", sid.id))
        )
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