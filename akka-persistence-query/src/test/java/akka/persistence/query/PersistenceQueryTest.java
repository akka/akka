/*
 * Copyright (C) 2009-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package akka.persistence.query;

import akka.NotUsed;
import akka.actor.ActorSystem;
import akka.testkit.AkkaJUnitActorSystemResource;
import com.typesafe.config.ConfigFactory;
import org.junit.ClassRule;


public class PersistenceQueryTest {

  @ClassRule
  public static AkkaJUnitActorSystemResource actorSystemResource =
    new AkkaJUnitActorSystemResource(PersistenceQueryTest.class.getName());

  private final ActorSystem system = actorSystemResource.getSystem();

  // compile-only test
  @SuppressWarnings("unused")
  public void shouldExposeJavaDSLFriendlyQueryJournal() throws Exception {
    final DummyJavaReadJournal readJournal = PersistenceQuery.get(system).getReadJournalFor(DummyJavaReadJournal.class,
        "noop-journal");
    final akka.stream.javadsl.Source<String, NotUsed> ids = readJournal.persistenceIds();
  }
}
