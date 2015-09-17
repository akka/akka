/*
   * Copyright (C) 2009-2015 Typesafe Inc. <http://www.typesafe.com>
   */

package akka.persistence.query;

import akka.actor.ActorSystem;
import akka.testkit.AkkaJUnitActorSystemResource;
import org.junit.ClassRule;
import scala.runtime.BoxedUnit;

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
    final akka.stream.javadsl.Source<String, BoxedUnit> ids = readJournal.allPersistenceIds();
  }
}
