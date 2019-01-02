/*
 * Copyright (C) 2018-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package jdocs.camel;

//#Producer1
import akka.camel.javaapi.UntypedProducerActor;
public class FirstProducer extends UntypedProducerActor {
  public String getEndpointUri() {
    return "http://localhost:8080/news";
  }
}
//#Producer1