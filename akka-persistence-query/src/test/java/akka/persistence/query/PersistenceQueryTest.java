/*
 * Copyright (C) 2009-2015 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.persistence.query;

import akka.actor.ActorSystem;
import akka.persistence.query.javadsl.ReadJournal;
import akka.testkit.AkkaJUnitActorSystemResource;
import org.junit.ClassRule;
import scala.runtime.BoxedUnit;

public class PersistenceQueryTest {

  @ClassRule
  public static AkkaJUnitActorSystemResource actorSystemResource =
    new AkkaJUnitActorSystemResource(PersistenceQueryTest.class.getName());

  private final ActorSystem system = actorSystemResource.getSystem();

  private final Hint hint = NoRefresh.getInstance();

  // compile-only test
  @SuppressWarnings("unused")
  public void shouldExposeJavaDSLFriendlyQueryJournal() throws Exception {
    final ReadJournal readJournal = PersistenceQuery.get(system).getReadJournalFor("noop-journal");
    final akka.stream.javadsl.Source<EventEnvelope, BoxedUnit> tag = readJournal.query(new EventsByTag("tag", 0L), hint, hint); // java varargs
  }
}
