/**
 * Copyright (C) 2009-2015 Typesafe Inc. <http://www.typesafe.com>
 */

package docs.persistence.query;

import java.util.HashSet;
import java.util.Set;
import scala.runtime.BoxedUnit;

import akka.actor.ActorSystem;
import akka.persistence.journal.WriteEventAdapter;
import akka.persistence.journal.EventSeq;
import akka.persistence.journal.leveldb.Tagged;
import akka.persistence.query.AllPersistenceIds;
import akka.persistence.query.EventEnvelope;
import akka.persistence.query.EventsByPersistenceId;
import akka.persistence.query.EventsByTag;
import akka.persistence.query.PersistenceQuery;
import akka.persistence.query.javadsl.ReadJournal;
import akka.persistence.query.journal.leveldb.LeveldbReadJournal;
import akka.stream.ActorMaterializer;
import akka.stream.javadsl.Source;

public class LeveldbPersistenceQueryDocTest {

  final ActorSystem system = ActorSystem.create();

  public void demonstrateReadJournal() {
    //#get-read-journal
    final ActorMaterializer mat = ActorMaterializer.create(system);
    
    ReadJournal queries =
      PersistenceQuery.get(system).getReadJournalFor(LeveldbReadJournal.Identifier());
    //#get-read-journal
  }
  
  public void demonstrateEventsByPersistenceId() {
    //#EventsByPersistenceId
    ReadJournal queries =
        PersistenceQuery.get(system).getReadJournalFor(LeveldbReadJournal.Identifier());
    
    Source<EventEnvelope, BoxedUnit> source =
        queries.query(EventsByPersistenceId.create("some-persistence-id", 0, Long.MAX_VALUE));
    //#EventsByPersistenceId
  }
  
  public void demonstrateAllPersistenceIds() {
    //#AllPersistenceIds
    ReadJournal queries =
        PersistenceQuery.get(system).getReadJournalFor(LeveldbReadJournal.Identifier());
    
    Source<String, BoxedUnit> source =
        queries.query(AllPersistenceIds.getInstance());
    //#AllPersistenceIds
  }
  
  public void demonstrateEventsByTag() {
    //#EventsByTag
    ReadJournal queries =
        PersistenceQuery.get(system).getReadJournalFor(LeveldbReadJournal.Identifier());
    
    Source<EventEnvelope, BoxedUnit> source =
        queries.query(EventsByTag.create("green", 0));
    //#EventsByTag
  }
  
  static 
  //#tagger
  public class MyEventAdapter implements WriteEventAdapter {

    @Override
    public Object toJournal(Object event) {
      if (event instanceof String) {
        String s = (String) event;
        Set<String> tags = new HashSet<String>();
        if (s.contains("green")) tags.add("green");
        if (s.contains("black")) tags.add("black");
        if (s.contains("blue")) tags.add("blue");
        if (tags.isEmpty())
          return event;
        else
          return new Tagged(event, tags);
      } else {
        return event;
      }
    }
    
    @Override
    public String manifest(Object event) {
      return "";
    }
  }
  //#tagger
}
