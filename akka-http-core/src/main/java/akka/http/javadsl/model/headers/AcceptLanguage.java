/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.javadsl.model.headers;

/**
 *  Model for the `Accept-Language` header.
 *  Specification: http://tools.ietf.org/html/draft-ietf-httpbis-p2-semantics-26#section-5.3.5
 */
public abstract class AcceptLanguage extends akka.http.scaladsl.model.HttpHeader {
    public abstract Iterable<LanguageRange> getLanguages();

    public static AcceptLanguage create(LanguageRange... languages) {
        return new akka.http.scaladsl.model.headers.Accept$minusLanguage(akka.http.impl.util.Util.<LanguageRange, akka.http.scaladsl.model.headers.LanguageRange>convertArray(languages));
    }
}
