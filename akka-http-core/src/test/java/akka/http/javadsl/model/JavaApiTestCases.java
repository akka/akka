/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.javadsl.model;

import akka.http.impl.util.Util;
import akka.http.javadsl.model.headers.Authorization;
import akka.http.javadsl.model.headers.HttpCredentials;
import akka.japi.Pair;

public class JavaApiTestCases {
    /** Builds a request for use on the client side */
    public static HttpRequest buildRequest() {
        return
            HttpRequest.create()
                .withMethod(HttpMethods.POST)
                .withUri("/send");
    }

    /** A simple handler for an Http server */
    public static HttpResponse handleRequest(HttpRequest request) {
        if (request.method() == HttpMethods.GET) {
            Uri uri = request.getUri();
            if (uri.path().equals("/hello")) {
                String name = Util.getOrElse(uri.query().get("name"), "Mister X");

                return
                    HttpResponse.create()
                        .withEntity("Hello " + name + "!");
            } else
                return
                    HttpResponse.create()
                        .withStatus(404)
                        .withEntity("Not found");
        } else
            return
                HttpResponse.create()
                    .withStatus(StatusCodes.METHOD_NOT_ALLOWED)
                    .withEntity("Unsupported method");
    }

    /** Adds authentication to an existing request */
    public static HttpRequest addAuthentication(HttpRequest request) {
        // unused here but just to show the shortcut
        request.addHeader(Authorization.basic("username", "password"));

        return request
            .addHeader(Authorization.create(HttpCredentials.createBasicHttpCredentials("username", "password")));

    }

    /** Removes cookies from an existing request */
    public static HttpRequest removeCookies(HttpRequest request) {
        return request.removeHeader("Cookie");
    }

    /** Build a uri to send a form */
    public static Uri createUriForOrder(String orderId, String price, String amount) {
        return Uri.create("/order").query(
                Query.create(
                        Pair.create("orderId", orderId),
                        Pair.create("price", price),
                        Pair.create("amount", amount)));
    }

    public static Query addSessionId(Query query) {
        return query.withParam("session", "abcdefghijkl");
    }
}
