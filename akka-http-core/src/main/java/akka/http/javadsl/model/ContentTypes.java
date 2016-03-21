/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http:__www.lightbend.com>
 */
package akka.http.javadsl.model;


import akka.http.scaladsl.model.ContentType$;

/**
 * Contains the set of predefined content-types for convenience.
 * <p>
 * If the {@link ContentType} you're looking for is not pre-defined here,
 * you can obtain it from a {@link MediaType} by using: {@code MediaTypes.TEXT_HTML.toContentType()}
 */
public final class ContentTypes {
  private ContentTypes() { }

  public static final ContentType.WithFixedCharset APPLICATION_JSON = MediaTypes.APPLICATION_JSON.toContentType();
  public static final ContentType.Binary APPLICATION_OCTET_STREAM = MediaTypes.APPLICATION_OCTET_STREAM.toContentType();

  public static final ContentType.WithCharset TEXT_PLAIN_UTF8 =
          akka.http.scaladsl.model.ContentTypes.text$divplain$u0028UTF$minus8$u0029();
  public static final ContentType.WithCharset TEXT_HTML_UTF8 =
          akka.http.scaladsl.model.ContentTypes.text$divhtml$u0028UTF$minus8$u0029();
  public static final ContentType.WithCharset TEXT_XML_UTF8 =
          akka.http.scaladsl.model.ContentTypes.text$divxml$u0028UTF$minus8$u0029();

  public static ContentType.Binary create(MediaType.Binary mediaType) {
    return ContentType$.MODULE$.apply((akka.http.scaladsl.model.MediaType.Binary) mediaType);
  }

  public static ContentType.WithFixedCharset create(MediaType.WithFixedCharset mediaType) {
    return ContentType$.MODULE$.apply((akka.http.scaladsl.model.MediaType.WithFixedCharset) mediaType);
  }

  public static ContentType.WithCharset create(MediaType.WithOpenCharset mediaType, HttpCharset charset) {
    return ContentType$.MODULE$.apply((akka.http.scaladsl.model.MediaType.WithOpenCharset) mediaType,
            (akka.http.scaladsl.model.HttpCharset) charset);
  }
}
