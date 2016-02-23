/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package docs.http.javadsl.server.testkit;

//#simple-app
import akka.http.javadsl.server.*;
import akka.http.javadsl.server.values.Parameters;

public class MyAppService extends HttpApp {
    RequestVal<Double> x = Parameters.doubleValue("x");
    RequestVal<Double> y = Parameters.doubleValue("y");

    public RouteResult add(RequestContext ctx, double x, double y) {
        return ctx.complete("x + y = " + (x + y));
    }

    @Override
    public Route createRoute() {
        return
            route(
                get(
                    pathPrefix("calculator").route(
                        path("add").route(
                            handleReflectively(this, "add", x, y)
                        )
                    )
                )
            );
    }
}
//#simple-app