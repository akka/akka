/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.javadsl.model;

import akka.japi.Pair;
import java.util.Map;

/**
 * Simple model for `application/x-www-form-urlencoded` form data.
 */
public final class FormData {

  private final Query fields;

  public FormData(Query fields) {
    this.fields = fields;
  }

  /**
   * Converts this FormData to a RequestEntity using UTF8 encoding.
   */
  public RequestEntity toEntity() {
    return toEntity(HttpCharsets.UTF_8);
  }

  /**
   * Converts this FormData to a RequestEntity using the given encoding.
   */
  public RequestEntity toEntity(HttpCharset charset) {
    return HttpEntities.create(ContentTypes.create(MediaTypes.APPLICATION_X_WWW_FORM_URLENCODED, charset), fields.render(charset));
  }

  /**
   * Returns empty FormData.
   */
  public static final FormData EMPTY = new FormData(Query.EMPTY);

  /**
   * Creates the FormData from the given parameters.
   */
  @SafeVarargs
  public static FormData create(Pair<String, String>... params) {
    return new FormData(Query.create(params));
  }

  /**
   * Creates the FormData from the given parameters.
   */
  public static FormData create(Map<String, String> params) {
    return new FormData(Query.create(params));
  }
}
