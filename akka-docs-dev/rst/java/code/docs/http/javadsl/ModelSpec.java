/*
 * Copyright (C) 2009-2015 Typesafe Inc. <http://www.typesafe.com>
 */

package docs.http.javadsl;

import akka.japi.Option;
import akka.util.ByteString;
import org.junit.Test;

//#import-model
import akka.http.javadsl.model.*;
import akka.http.javadsl.model.headers.*;
//#import-model

public class ModelSpec {
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
                .withEntity(HttpEntities.create(MediaTypes.TEXT_PLAIN.toContentType(), "abc"))
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
    private Option<BasicHttpCredentials> getCredentialsOfRequest(HttpRequest request) {
        Option<Authorization> auth = request.getHeader(Authorization.class);
        if (auth.isDefined() && auth.get().credentials() instanceof BasicHttpCredentials)
            return Option.some((BasicHttpCredentials) auth.get().credentials());
        else
            return Option.none();
    }
    //#headers
}
