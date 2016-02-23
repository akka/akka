/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package docs.http.javadsl;

import akka.util.ByteString;
import org.junit.Test;

//#import-model
import akka.http.javadsl.model.*;
import akka.http.javadsl.model.headers.*;

import java.util.Optional;
//#import-model

@SuppressWarnings("unused")
public class ModelDocTest {
    @Test
    public void testConstructRequest() {
        //#construct-request
        // construct a simple GET request to `homeUri`
        Uri homeUri = Uri.create("/home");
        HttpRequest request1 = HttpRequest.create().withUri(homeUri);

        // construct simple GET request to "/index" using helper methods
        HttpRequest request2 = HttpRequest.GET("/index");

        // construct simple POST request containing entity
        ByteString data = ByteString.fromString("abc");
        HttpRequest postRequest1 = HttpRequest.POST("/receive").withEntity(data);

        // customize every detail of HTTP request
        //import HttpProtocols._
        //import MediaTypes._
        Authorization authorization = Authorization.basic("user", "pass");
        HttpRequest complexRequest =
            HttpRequest.PUT("/user")
                .withEntity(HttpEntities.create(ContentTypes.TEXT_PLAIN_UTF8, "abc"))
                .addHeader(authorization)
                .withProtocol(HttpProtocols.HTTP_1_0);
        //#construct-request
    }

    @Test
    public void testConstructResponse() {
        //#construct-response
        // simple OK response without data created using the integer status code
        HttpResponse ok = HttpResponse.create().withStatus(200);

        // 404 response created using the named StatusCode constant
        HttpResponse notFound = HttpResponse.create().withStatus(StatusCodes.NOT_FOUND);

        // 404 response with a body explaining the error
        HttpResponse notFoundCustom =
            HttpResponse.create()
                .withStatus(404)
                .withEntity("Unfortunately, the resource couldn't be found.");

        // A redirecting response containing an extra header
        Location locationHeader = Location.create("http://example.com/other");
        HttpResponse redirectResponse =
            HttpResponse.create()
                .withStatus(StatusCodes.FOUND)
                .addHeader(locationHeader);
        //#construct-response
    }

    @Test
    public void testDealWithHeaders() {
        //#headers
        // create a ``Location`` header
        Location locationHeader = Location.create("http://example.com/other");

        // create an ``Authorization`` header with HTTP Basic authentication data
        Authorization authorization = Authorization.basic("user", "pass");
        //#headers
    }

    //#headers

    // a method that extracts basic HTTP credentials from a request
	private Optional<BasicHttpCredentials> getCredentialsOfRequest(HttpRequest request) {
        Optional<Authorization> auth = request.getHeader(Authorization.class);
        if (auth.isPresent() && auth.get().credentials() instanceof BasicHttpCredentials)
            return Optional.of((BasicHttpCredentials) auth.get().credentials());
        else
            return Optional.empty();
    }
    //#headers
}
