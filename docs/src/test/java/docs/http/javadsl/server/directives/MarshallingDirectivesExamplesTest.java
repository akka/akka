/*
 * Copyright (C) 2015-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package docs.http.javadsl.server.directives;

import akka.http.javadsl.marshallers.jackson.Jackson;
import akka.http.javadsl.marshalling.Marshaller;
import akka.http.javadsl.model.*;
import akka.http.javadsl.server.Route;
import akka.http.javadsl.testkit.JUnitRouteTest;
import akka.http.javadsl.testkit.TestRouteResult;
import akka.http.javadsl.unmarshalling.Unmarshaller;
import org.junit.Test;

import java.util.function.Consumer;
import java.util.function.Function;

public class MarshallingDirectivesExamplesTest extends JUnitRouteTest {

  //#person
  static public class Person {
    private final String name;
    private final int favoriteNumber;

    //default constructor required for Jackson
    public Person() {
      this.name = "";
      this.favoriteNumber = 0;
    }

    public Person(String name, int favoriteNumber) {
      this.name = name;
      this.favoriteNumber = favoriteNumber;
    }

    public String getName() {
      return name;
    }

    public int getFavoriteNumber() {
      return favoriteNumber;
    }
  }
  //#person

  @Test
  public void testEntity() {
    //#example-entity-with-json
    final Unmarshaller<HttpEntity, Person> unmarshallar = Jackson.unmarshaller(Person.class);

    final Route route = entity(unmarshallar, person ->
      complete( "Person:" +  person.getName() + " - favoriteNumber:" + person.getFavoriteNumber() )
    );

    testRoute(route).run(
      HttpRequest.POST("/")
        .withEntity(
          HttpEntities.create(
            ContentTypes.APPLICATION_JSON, "{\"name\":\"Jane\",\"favoriteNumber\":42}"
          )
        )
    ).assertEntity("Person:Jane - favoriteNumber:42");
    //#example-entity-with-json
  }

  @Test
  public void testCompleteWith() {
    //#example-completeWith-with-json
    final Marshaller<Person, HttpResponse> marshaller = Marshaller.entityToOKResponse(Jackson.<Person>marshaller());

    //Please note that you can also pass completionFunction to another thread and use it there to complete the request.
    //For example:
    //final Consumer<Consumer<Person>> findPerson = completionFunction -> {
    //  CompletableFuture.runAsync(() ->
    //   /* ... some processing logic... */
    //   completionFunction.accept(new Person("Jane", 42)));
    //};
    final Consumer<Consumer<Person>> findPerson = completionFunction -> {

      //... some processing logic...

      //complete the request
      completionFunction.accept(new Person("Jane", 42));
    };

    final Route route = completeWith(marshaller, findPerson);

    // tests:
    final TestRouteResult routeResult = testRoute(route).run(
            HttpRequest.GET("/")
    );
    routeResult.assertMediaType(MediaTypes.APPLICATION_JSON);
    routeResult.assertEntity("{\"favoriteNumber\":42,\"name\":\"Jane\"}");
    //#example-completeWith-with-json
  }

  @Test
  public void testHandleWith() {
    //#example-handleWith-with-json
    final Unmarshaller<HttpEntity, Person> unmarshaller = Jackson.unmarshaller(Person.class);
    final Marshaller<Person, HttpResponse> marshaller = Marshaller.entityToOKResponse(Jackson.<Person>marshaller());

    final Function<Person, Person> updatePerson = person -> {

      //... some processing logic...

      //return the person
      return person;
    };

    final Route route = handleWith(unmarshaller, marshaller, updatePerson);

    // tests:
    final TestRouteResult routeResult = testRoute(route).run(
      HttpRequest.POST("/")
        .withEntity(
          HttpEntities.create(
            ContentTypes.APPLICATION_JSON, "{\"name\":\"Jane\",\"favoriteNumber\":42}"
          )
        )
    );
    routeResult.assertMediaType(MediaTypes.APPLICATION_JSON);
    routeResult.assertEntity("{\"favoriteNumber\":42,\"name\":\"Jane\"}");
    //#example-handleWith-with-json
  }
}
