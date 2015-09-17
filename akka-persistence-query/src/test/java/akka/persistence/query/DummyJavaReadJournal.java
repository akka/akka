/*
 * Copyright (C) 2009-2015 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.persistence.query;

import scala.runtime.BoxedUnit;

import java.util.Iterator;

import akka.persistence.query.javadsl.AllPersistenceIdsQuery;
import akka.persistence.query.javadsl.ReadJournal;
import akka.stream.javadsl.Source;

/**
 * Use for tests only!
 * Emits infinite stream of strings (representing queried for events).
 */
public class DummyJavaReadJournal implements ReadJournal, AllPersistenceIdsQuery {
  public static final String Identifier = "akka.persistence.query.journal.dummy-java";


  @Override
  public Source<String, BoxedUnit> allPersistenceIds() {
    return Source.fromIterator(() -> new Iterator<String>() {
      private int i = 0;
      @Override public boolean hasNext() { return true; }

      @Override public String next() {
        return "" + (i++);
      }
    });
  }
}

