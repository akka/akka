/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.javadsl.model.headers;

import akka.http.scaladsl.model.headers.HttpOrigin$;

public abstract class HttpOrigin {
    public abstract String scheme();
    public abstract Host host();

    public static HttpOrigin create(String scheme, Host host) {
        return new akka.http.scaladsl.model.headers.HttpOrigin(scheme, (akka.http.scaladsl.model.headers.Host) host);
    }
    public static HttpOrigin parse(String originString) {
        return HttpOrigin$.MODULE$.apply(originString);
    }
}
