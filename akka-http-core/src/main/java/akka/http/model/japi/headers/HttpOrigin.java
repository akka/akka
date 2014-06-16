/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.model.japi.headers;

import akka.http.model.headers.HttpOrigin$;

public abstract class HttpOrigin {
    public abstract String scheme();
    public abstract Host host();

    public static HttpOrigin create(String scheme, Host host) {
        return new akka.http.model.headers.HttpOrigin(scheme, (akka.http.model.headers.Host) host);
    }
    public static HttpOrigin parse(String originString) {
        return HttpOrigin$.MODULE$.apply(originString);
    }
}
