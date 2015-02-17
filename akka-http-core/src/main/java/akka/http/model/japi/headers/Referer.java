/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.model.japi.headers;

import akka.http.model.japi.Uri;

/**
 *  Model for the `Referer` header.
 *  Specification: http://tools.ietf.org/html/rfc7231#section-5.5.2
 */
public abstract class Referer extends akka.http.model.HttpHeader {
    public abstract Uri getUri();

    public static Referer create(Uri uri) {
        return new akka.http.model.headers.Referer(akka.http.model.japi.Util.convertUriToScala(uri));
    }
}
