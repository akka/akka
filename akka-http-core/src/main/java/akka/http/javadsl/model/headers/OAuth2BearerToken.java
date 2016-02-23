/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */

package akka.http.javadsl.model.headers;

public abstract class OAuth2BearerToken extends akka.http.scaladsl.model.headers.HttpCredentials {
    public abstract String token();
}
