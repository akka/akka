/**
 * Copyright (C) 2009-2016 Lightbend Inc. <http://www.lightbend.com>
 */


package akka.http.javadsl.model;

public final class RequestEntityAcceptances {
  private RequestEntityAcceptances() {}

  public static final RequestEntityAcceptance Expected   = akka.http.scaladsl.model.RequestEntityAcceptance.Expected$.MODULE$;
  public static final RequestEntityAcceptance Tolerated  = akka.http.scaladsl.model.RequestEntityAcceptance.Tolerated$.MODULE$;
  public static final RequestEntityAcceptance Disallowed = akka.http.scaladsl.model.RequestEntityAcceptance.Disallowed$.MODULE$;
}
