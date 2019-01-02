/*
 * Copyright (C) 2018-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package jdocs.camel;
//#Oneway
import akka.camel.javaapi.UntypedProducerActor;

public class OnewaySender extends UntypedProducerActor{
  private String uri;

  public OnewaySender(String uri) {
    this.uri = uri;
  }
  public String getEndpointUri() {
    return uri;
  }

  @Override
  public boolean isOneway() {
    return true;
  }
}
//#Oneway
