/**
 * Copyright (C) 2009-2014 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.http.model.japi.headers;

public abstract class BasicHttpCredentials extends akka.http.model.headers.HttpCredentials {
    public abstract String username();
    public abstract String password();
}
