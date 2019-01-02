/*
 * Copyright (C) 2018-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package jdocs.camel;
//#ProducerTemplate
import akka.actor.UntypedAbstractActor;
import akka.camel.Camel;
import akka.camel.CamelExtension;
import org.apache.camel.ProducerTemplate;

public class MyActor extends UntypedAbstractActor {
  public void onReceive(Object message) {
    Camel camel = CamelExtension.get(getContext().getSystem());
    ProducerTemplate template = camel.template();
    template.sendBody("direct:news", message);
  }
}
//#ProducerTemplate