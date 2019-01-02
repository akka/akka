/*
 * Copyright (C) 2018-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package jdocs.camel;
//#Producer
import akka.camel.javaapi.UntypedProducerActor;

public class Orders extends UntypedProducerActor {
  public String getEndpointUri() {
    return "jms:queue:Orders";
  }
}
//#Producer