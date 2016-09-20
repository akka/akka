/*
 * Copyright (C) 2015-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package docs.http.javadsl.server.directives;

import akka.http.javadsl.marshallers.jackson.Jackson;
import akka.http.javadsl.model.*;
import akka.http.javadsl.server.Route;
import akka.http.javadsl.testkit.JUnitRouteTest;
import akka.http.javadsl.unmarshalling.Unmarshaller;
import org.junit.Test;

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
}
