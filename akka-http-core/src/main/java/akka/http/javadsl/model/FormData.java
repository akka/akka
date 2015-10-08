/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.javadsl.model;

import akka.http.impl.model.parser.CharacterClasses;
import akka.http.impl.util.JavaMapping;
import akka.http.impl.util.StringRendering;
import akka.http.scaladsl.model.Uri.Query;
import akka.http.scaladsl.model.UriRendering;
import akka.japi.Pair;

/**
 * Simple model for `application/x-www-form-urlencoded` form data.
 */
public abstract class FormData {

  public abstract Query fields();

  public RequestEntity toEntity() {
    return toEntity(HttpCharsets.UTF_8);
  }

  public RequestEntity toEntity(HttpCharset charset) {
    // TODO this logic is duplicated in scaladsl.model.FormData, spent hours trying to DRY it but compiler freaked out in a number of ways... -- ktoso
    final akka.http.scaladsl.model.HttpCharset c = (akka.http.scaladsl.model.HttpCharset) charset;
    final StringRendering render = (StringRendering) UriRendering.renderQuery(new StringRendering(), this.fields(), c.nioCharset(), CharacterClasses.unreserved());
    return HttpEntities.create(ContentType.create(MediaTypes.APPLICATION_X_WWW_FORM_URLENCODED, charset), render.get());
  }

  @SafeVarargs
  public static FormData create(Pair<String, String>... fields) {
    return akka.http.scaladsl.model.FormData.create(fields);
  }

}
