/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.javadsl.model.headers;

import akka.http.javadsl.model.MediaType;
import akka.http.javadsl.model.Uri;
import akka.http.impl.util.Util;

public final class LinkParams {
    private LinkParams() {}

    public static final LinkParam next = akka.http.scaladsl.model.headers.LinkParams.next();
    public static final LinkParam prev = akka.http.scaladsl.model.headers.LinkParams.prev();
    public static final LinkParam first = akka.http.scaladsl.model.headers.LinkParams.first();
    public static final LinkParam last = akka.http.scaladsl.model.headers.LinkParams.last();

    public static LinkParam rel(String value) {
        return new akka.http.scaladsl.model.headers.LinkParams.rel(value);
    }
    public static LinkParam anchor(Uri uri) {
        return new akka.http.scaladsl.model.headers.LinkParams.anchor(Util.convertUriToScala(uri));
    }
    public static LinkParam rev(String value) {
        return new akka.http.scaladsl.model.headers.LinkParams.rev(value);
    }
    public static LinkParam hreflang(Language language) {
        return new akka.http.scaladsl.model.headers.LinkParams.hreflang((akka.http.scaladsl.model.headers.Language) language);
    }
    public static LinkParam media(String desc) {
        return new akka.http.scaladsl.model.headers.LinkParams.media(desc);
    }
    public static LinkParam title(String title) {
        return new akka.http.scaladsl.model.headers.LinkParams.title(title);
    }
    public static LinkParam title_All(String title) {
        return new akka.http.scaladsl.model.headers.LinkParams.title$times(title);
    }
    public static LinkParam type(MediaType type) {
        return new akka.http.scaladsl.model.headers.LinkParams.type((akka.http.scaladsl.model.MediaType) type);
    }
}
