/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.model.japi.headers;

import akka.http.model.japi.Uri;
import akka.http.model.japi.Util;

public abstract class LinkValue {
    public abstract Uri getUri();
    public abstract Iterable<LinkParam> getParams();

    public static LinkValue create(Uri uri, LinkParam... params) {
        return new akka.http.model.headers.LinkValue(
                Util.convertUriToScala(uri),
                Util.<LinkParam, akka.http.model.headers.LinkParam>convertArray(params));
    }
}
