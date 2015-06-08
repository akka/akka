/*
 * Copyright (C) 2009-2015 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.persistence.query;

import akka.persistence.query.javadsl.ReadJournal;
import akka.stream.javadsl.Source;
import com.typesafe.config.Config;
import com.typesafe.config.ConfigFactory;

import java.util.Iterator;

/**
 * Use for tests only!
 * Emits infinite stream of strings (representing queried for events).
 */
class MockJavaReadJournal implements ReadJournal {
  public static final String Identifier = "akka.persistence.query.journal.mock-java";

  public static final Config config = ConfigFactory.parseString(
    Identifier + " { \n" +
      "   class = \"" + MockJavaReadJournal.class.getCanonicalName() + "\" \n" +
      " }\n\n");

  @Override
  @SuppressWarnings("unchecked")
  public <T, M> Source<T, M> query(Query<T, M> q, Hint... hints) {
    return (Source<T, M>) Source.fromIterator(() -> new Iterator<String>() {
      private int i = 0;
      @Override public boolean hasNext() { return true; }

      @Override public String next() {
        return "" + (i++);
      }
    });
  }
}