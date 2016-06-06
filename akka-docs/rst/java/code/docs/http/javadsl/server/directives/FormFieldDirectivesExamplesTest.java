/*
 * Copyright (C) 2016-2016 Lightbend Inc. <http://www.lightbend.com>
 */
package docs.http.javadsl.server.directives;

import akka.http.javadsl.model.FormData;
import akka.http.javadsl.model.HttpRequest;
import akka.http.javadsl.model.StatusCodes;
import akka.http.javadsl.server.Route;
import akka.http.javadsl.server.StringUnmarshallers;
import akka.http.javadsl.testkit.JUnitRouteTest;
import akka.japi.Pair;
import org.junit.Test;

import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.function.Function;
import java.util.stream.Collectors;

public class FormFieldDirectivesExamplesTest extends JUnitRouteTest {

  @Test
  public void testFormField() {
    //#formField
    final Route route = route(
      formField("color", color ->
        complete("The color is '" + color + "'")
      ),
      formField(StringUnmarshallers.INTEGER, "id", id ->
        complete("The id is '" + id + "'")
      )
    );

    // tests:
    final FormData formData = FormData.create(Pair.create("color", "blue"));
    testRoute(route).run(HttpRequest.POST("/").withEntity(formData.toEntity()))
      .assertEntity("The color is 'blue'");

    testRoute(route).run(HttpRequest.GET("/"))
      .assertStatusCode(StatusCodes.BAD_REQUEST)
      .assertEntity("Request is missing required form field 'color'");
    //#formField
  }

  @Test
  public void testFormFieldMap() {
    //#formFieldMap
    final Function<Map<String, String>, String> mapToString = map ->
      map.entrySet()
        .stream()
        .map(e -> e.getKey() + " = '" + e.getValue() +"'")
        .collect(Collectors.joining(", "));


    final Route route = formFieldMap(fields ->
      complete("The form fields are " + mapToString.apply(fields))
    );

    // tests:
    final FormData formDataDiffKey =
      FormData.create(
        Pair.create("color", "blue"),
        Pair.create("count", "42"));
    testRoute(route).run(HttpRequest.POST("/").withEntity(formDataDiffKey.toEntity()))
      .assertEntity("The form fields are color = 'blue', count = '42'");

    final FormData formDataSameKey =
      FormData.create(
        Pair.create("x", "1"),
        Pair.create("x", "5"));
    testRoute(route).run(HttpRequest.POST("/").withEntity(formDataSameKey.toEntity()))
      .assertEntity( "The form fields are x = '5'");
    //#formFieldMap
  }

  @Test
  public void testFormFieldMultiMap() {
    //#formFieldMultiMap
    final Function<Map<String, List<String>>, String> mapToString = map ->
      map.entrySet()
        .stream()
        .map(e -> e.getKey() + " -> " + e.getValue().size())
        .collect(Collectors.joining(", "));

    final Route route = formFieldMultiMap(fields ->
      complete("There are form fields " + mapToString.apply(fields))
    );

    // test:
    final FormData formDataDiffKey =
      FormData.create(
        Pair.create("color", "blue"),
        Pair.create("count", "42"));
    testRoute(route).run(HttpRequest.POST("/").withEntity(formDataDiffKey.toEntity()))
      .assertEntity("There are form fields color -> 1, count -> 1");

    final FormData formDataSameKey =
      FormData.create(
        Pair.create("x", "23"),
        Pair.create("x", "4"),
        Pair.create("x", "89"));
    testRoute(route).run(HttpRequest.POST("/").withEntity(formDataSameKey.toEntity()))
      .assertEntity("There are form fields x -> 3");
    //#formFieldMultiMap
  }

  @Test
  public void testFormFieldList() {
    //#formFieldList
    final Function<List<Entry<String, String>>, String> listToString = list ->
      list.stream()
        .map(e -> e.getKey() + " = '" + e.getValue() +"'")
        .collect(Collectors.joining(", "));

    final Route route = formFieldList(fields ->
      complete("The form fields are " + listToString.apply(fields))
    );

    // tests:
    final FormData formDataDiffKey =
      FormData.create(
        Pair.create("color", "blue"),
        Pair.create("count", "42"));
    testRoute(route).run(HttpRequest.POST("/").withEntity(formDataDiffKey.toEntity()))
      .assertEntity("The form fields are color = 'blue', count = '42'");

    final FormData formDataSameKey =
      FormData.create(
        Pair.create("x", "23"),
        Pair.create("x", "4"),
        Pair.create("x", "89"));
    testRoute(route).run(HttpRequest.POST("/").withEntity(formDataSameKey.toEntity()))
      .assertEntity("The form fields are x = '23', x = '4', x = '89'");
    //#formFieldList
  }
}
