/*
 * Copyright (C) 2009-2017 Lightbend Inc. <http://www.lightbend.com>
 */

package docs.http.javadsl.server.testkit;
//#source-quote
import akka.http.javadsl.server.AllDirectives;
import akka.http.javadsl.server.Route;

public class MyAppFragment extends AllDirectives {
    public Route createRoute() {
        return
                pathEnd(() ->
                        get(() ->
                                complete("Fragments of imagination")
                        )
                );

    }

}
//#source-quote
