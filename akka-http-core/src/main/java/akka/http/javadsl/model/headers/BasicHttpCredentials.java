/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.javadsl.model.headers;

public abstract class BasicHttpCredentials extends akka.http.scaladsl.model.headers.HttpCredentials {
    public abstract String username();
    public abstract String password();
}
