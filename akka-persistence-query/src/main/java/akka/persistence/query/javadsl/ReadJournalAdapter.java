/*
 * Copyright (C) 2009-2015 Typesafe Inc. <http://www.typesafe.com>
 */

package akka.persistence.query.javadsl;

import akka.japi.Util;
import akka.persistence.query.Hint;
import akka.persistence.query.Query;
import akka.stream.javadsl.Source;

/**
 * INTERNAL API
 *
 * Adapter from ScalaDSL {@link akka.persistence.query.scaladsl.ReadJournal}
 * to JavaDSL {@link ReadJournal}.
 */
public final class ReadJournalAdapter implements ReadJournal {

  private final akka.persistence.query.scaladsl.ReadJournal backing;

  public ReadJournalAdapter(akka.persistence.query.scaladsl.ReadJournal backing) {
    this.backing = backing;
  }

  @Override
  public <T, M> Source<T, M> query(Query<T, M> q, Hint... hints) {
    return backing.query(q, Util.immutableSeq(hints)).asJava();
  }

}
