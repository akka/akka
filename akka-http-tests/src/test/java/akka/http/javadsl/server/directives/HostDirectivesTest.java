/*
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.javadsl.server.directives;

import akka.http.javadsl.model.HttpRequest;
import akka.http.javadsl.model.Uri;
import akka.http.javadsl.testkit.JUnitRouteTest;
import akka.http.javadsl.testkit.TestRoute;
import akka.japi.function.Function;
import org.junit.Test;

import java.util.ArrayList;

public class HostDirectivesTest extends JUnitRouteTest {
    @Test
    public void testHostFilterBySingleName() {
        TestRoute route = testRoute(host("example.org", complete("OK!")));

        route
            .run(HttpRequest.create().withUri(Uri.create("http://example.org")))
            .assertStatusCode(200)
            .assertEntity("OK!");

        route
            .run(HttpRequest.create().withUri(Uri.create("https://other.org")))
            .assertStatusCode(404);
    }
    @Test
    public void testHostFilterByNames() {
        ArrayList<String> hosts = new ArrayList<String>();
        hosts.add("example.org");
        hosts.add("example2.org");
        TestRoute route = testRoute(host(hosts, complete("OK!")));

        route
            .run(HttpRequest.create().withUri(Uri.create("http://example.org")))
            .assertStatusCode(200)
            .assertEntity("OK!");

        route
            .run(HttpRequest.create().withUri(Uri.create("http://example2.org")))
            .assertStatusCode(200)
            .assertEntity("OK!");

        route
            .run(HttpRequest.create().withUri(Uri.create("https://other.org")))
            .assertStatusCode(404);
    }
    @Test
    public void testHostFilterByPredicate() {
        Function<String, Boolean> predicate =
            new Function<String, Boolean>(){
                @Override
                public Boolean apply(String hostName) throws Exception {
                    return hostName.contains("ample");
                }
            };

        TestRoute route = testRoute(host(predicate, complete("OK!")));

        route
            .run(HttpRequest.create().withUri(Uri.create("http://example.org")))
            .assertStatusCode(200)
            .assertEntity("OK!");

        route
            .run(HttpRequest.create().withUri(Uri.create("https://other.org")))
            .assertStatusCode(404);
    }
}
