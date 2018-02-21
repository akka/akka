/**
 * Copyright (C) 2009-2018 Lightbend Inc. <http://www.lightbend.com>
 */
package akka.actor.typed.receptionist;


import akka.actor.typed.ActorRef;

public class ReceptionistApiTest {

  public void compileOnlyApiTest() {
    ActorRef<String> ref = null;
    ActorRef<Receptionist.Registered<String>> respondTo = null;
    ServiceKey<String> key = ServiceKey.create(String.class, "id");
    Receptionist.Register register = new Receptionist.Register<>(key, ref, respondTo);
    Receptionist.Register registerNoAck = new Receptionist.Register<>(key, ref);

    ActorRef<Receptionist.Listing<String>> listingRecipient = null;
    Receptionist.Find find = new Receptionist.Find<>(key, listingRecipient);

    Receptionist.Subscribe subscribe = new Receptionist.Subscribe<>(key, listingRecipient);
  }
}
