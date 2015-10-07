/*
 * Copyright (C) 2009-2015 Typesafe Inc. <http://www.typesafe.com>
 */

package docs.http.javadsl.server;

import akka.http.javadsl.model.ContentTypes;
import akka.http.javadsl.model.HttpRequest;
import akka.http.javadsl.model.headers.RawHeader;
import akka.http.javadsl.server.Marshallers;
import akka.http.javadsl.server.RequestVal;
import akka.http.javadsl.server.Route;
import akka.http.javadsl.server.values.FormField;
import akka.http.javadsl.server.values.FormFields;
import akka.http.javadsl.server.values.Headers;
import akka.http.javadsl.testkit.JUnitRouteTest;
import docs.http.scaladsl.server.directives.Person;
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
    final HttpRequest request =
      HttpRequest
        .POST("/");
        // .withFormData(); // FIXME awaits resolution of https://github.com/akka/akka/issues/18665
    testRoute(route).run(request).assertEntity("Name: ..., age: ...");

    //#simple
  }

  @Test
  public void testFormFieldValsUnmarshaling() {
    //#custom-unmarshal
    FormField<SampleId> sampleId =  FormFields.fromString("id", s -> new SampleId(Integer.valueOf(s)), SampleId.class);

    final Route route =
      route(
        handleWith1(sampleId, (ctx, id) ->
          ctx.complete(String.format("SampleId: %s", id))
        )
      );

    // tests:
    final HttpRequest request =
      HttpRequest
        .POST("/");
        // .withFormData(); // FIXME awaits resolution of https://github.com/akka/akka/issues/18665
    testRoute(route).run(request).assertEntity("Name: ..., age: ...");

    //#custom-unmarshal
  }

  static class SampleId {
    public final int id;

    SampleId(int id) {
      this.id = id;
    }
  }

}