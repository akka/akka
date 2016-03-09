/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package docs.http.javadsl.server.testkit;

//#simple-app
import akka.http.javadsl.server.HttpApp;
import akka.http.javadsl.server.Route;
import akka.http.javadsl.server.StringUnmarshallers;

public class MyAppService extends HttpApp {

    public String add(double x, double y) {
        return "x + y = " + (x + y);
    }

    @Override
    public Route createRoute() {
        return
            get(() ->
                pathPrefix("calculator", () ->
                    path("add", () ->
                        param(StringUnmarshallers.INTEGER, "x", x ->
                            param(StringUnmarshallers.INTEGER, "y", y ->
                                complete(add(x,y))
                            )
                        )
                    )
                )
            );
    }
}
//#simple-app