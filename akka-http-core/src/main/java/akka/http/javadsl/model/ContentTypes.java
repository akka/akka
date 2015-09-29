/**
 * Copyright (C) 2009-2015 Typesafe Inc. <http:__www.typesafe.com>
 */
package akka.http.javadsl.model;


/**
 * Contains the set of predefined content-types.
 * <p>
 * If the {@link ContentType} you're looking for is not pre-defined here,
 * you can obtain it from a {@link MediaType} by using:
 * <p>
 * {@code MediaTypes.TEXT_HTML.toContentType()}
 */
public final class ContentTypes {
    public static final ContentType APPLICATION_JSON = MediaTypes.APPLICATION_JSON.toContentType();
    public static final ContentType TEXT_PLAIN = MediaTypes.TEXT_PLAIN.toContentType();
    public static final ContentType TEXT_PLAIN_UTF8 = akka.http.scaladsl.model.ContentTypes.text$divplain$u0028UTF$minus8$u0029();
    public static final ContentType TEXT_HTML = MediaTypes.TEXT_HTML.toContentType();
    public static final ContentType APPLICATION_OCTET_STREAM = MediaTypes.APPLICATION_OCTET_STREAM.toContentType();
}
