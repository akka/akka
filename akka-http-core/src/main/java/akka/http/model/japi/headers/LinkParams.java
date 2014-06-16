/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.model.japi.headers;

import akka.http.model.japi.MediaType;
import akka.http.model.japi.Uri;
import akka.http.model.japi.Util;

public final class LinkParams {
    private LinkParams() {}

    public static final LinkParam next = akka.http.model.headers.LinkParams.next();
    public static final LinkParam prev = akka.http.model.headers.LinkParams.prev();
    public static final LinkParam first = akka.http.model.headers.LinkParams.first();
    public static final LinkParam last = akka.http.model.headers.LinkParams.last();

    public static LinkParam rel(String value) {
        return new akka.http.model.headers.LinkParams.rel(value);
    }
    public static LinkParam anchor(Uri uri) {
        return new akka.http.model.headers.LinkParams.anchor(Util.convertUriToScala(uri));
    }
    public static LinkParam rev(String value) {
        return new akka.http.model.headers.LinkParams.rev(value);
    }
    public static LinkParam hreflang(Language language) {
        return new akka.http.model.headers.LinkParams.hreflang((akka.http.model.headers.Language) language);
    }
    public static LinkParam media(String desc) {
        return new akka.http.model.headers.LinkParams.media(desc);
    }
    public static LinkParam title(String title) {
        return new akka.http.model.headers.LinkParams.title(title);
    }
    public static LinkParam title_All(String title) {
        return new akka.http.model.headers.LinkParams.title$times(title);
    }
    public static LinkParam type(MediaType type) {
        return new akka.http.model.headers.LinkParams.type((akka.http.model.MediaType) type);
    }
}
