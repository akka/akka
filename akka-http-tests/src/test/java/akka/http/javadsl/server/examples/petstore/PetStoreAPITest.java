/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.javadsl.server.examples.petstore;

import akka.http.javadsl.marshallers.jackson.Jackson;
import akka.http.javadsl.model.HttpRequest;
import akka.http.javadsl.model.MediaTypes;
import akka.http.javadsl.testkit.*;
import static org.junit.Assert.*;
import akka.http.javadsl.testkit.TestRoute;

import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

public class PetStoreAPITest extends JUnitRouteTest {
    @Test
    public void testGetPet() {
        TestRouteResult response = createRoute().run(HttpRequest.GET("/pet/1"));

        response
            .assertStatusCode(200)
            .assertMediaType("application/json");

        Pet pet = response.entity(Jackson.unmarshaller(Pet.class));
        assertEquals("cat", pet.getName());
        assertEquals(1, pet.getId());
    }
    @Test
    public void testGetMissingPet() {
        createRoute().run(HttpRequest.GET("/pet/999"))
            .assertStatusCode(404);
    }
    @Test
    public void testPutPet() {
        HttpRequest request =
            HttpRequest.PUT("/pet/1")
                .withEntity(MediaTypes.APPLICATION_JSON.toContentType(), "{\"id\": 1, \"name\": \"giraffe\"}");

        TestRouteResult response = createRoute().run(request);

        response.assertStatusCode(200);

        Pet pet = response.entity(Jackson.unmarshaller(Pet.class));
        assertEquals("giraffe", pet.getName());
        assertEquals(1, pet.getId());
    }
    @Test
    public void testDeletePet() {
        Map<Integer, Pet> data = createData();

        HttpRequest request = HttpRequest.DELETE("/pet/0");

        createRoute(data).run(request)
            .assertStatusCode(200);

        // test actual deletion from data store
        assertFalse(data.containsKey(0));
    }

    private TestRoute createRoute() {
        return createRoute(createData());
    }
    private TestRoute createRoute(Map<Integer, Pet> pets) {
        return testRoute(PetStoreExample.appRoute(pets));
    }
    private Map<Integer, Pet> createData() {
        Map<Integer, Pet> pets = new HashMap<Integer, Pet>();
        Pet dog = new Pet(0, "dog");
        Pet cat = new Pet(1, "cat");
        pets.put(0, dog);
        pets.put(1, cat);

        return pets;
    }
}
