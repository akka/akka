/*
 * Copyright (C) 2018-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package jdocs.camel;

import akka.actor.ActorSystem;
import akka.camel.Camel;
import akka.camel.CamelExtension;
import jdocs.AbstractJavaTest;
import akka.testkit.javadsl.TestKit;
import org.apache.camel.CamelContext;
import org.apache.camel.ProducerTemplate;
import org.junit.Test;

public class CamelExtensionTest extends AbstractJavaTest {
  @Test
  public void getCamelExtension() {
    // #CamelExtension
    ActorSystem system = ActorSystem.create("some-system");
    Camel camel = CamelExtension.get(system);
    CamelContext camelContext = camel.context();
    ProducerTemplate producerTemplate = camel.template();
    // #CamelExtension
    TestKit.shutdownActorSystem(system);
  }

  public void addActiveMQComponent() {
    // #CamelExtensionAddComponent
    ActorSystem system = ActorSystem.create("some-system");
    Camel camel = CamelExtension.get(system);
    CamelContext camelContext = camel.context();
    // camelContext.addComponent("activemq", ActiveMQComponent.activeMQComponent(
    //   "vm://localhost?broker.persistent=false"));
    // #CamelExtensionAddComponent
    TestKit.shutdownActorSystem(system);
  }
}
