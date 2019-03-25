/*
 * Copyright (C) 2015-2019 Lightbend Inc. <https://www.lightbend.com>
 */

package jdocs.persistence.query;

import akka.actor.Cancellable;
import akka.actor.Scheduler;
import akka.japi.Pair;
import akka.persistence.PersistentRepr;
import akka.persistence.query.Offset;
import akka.serialization.Serialization;
import akka.serialization.SerializationExtension;
import akka.stream.actor.AbstractActorPublisher;

import akka.actor.Props;
import akka.persistence.query.EventEnvelope;
import akka.stream.actor.ActorPublisherMessage.Cancel;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.time.Duration;

import static java.util.stream.Collectors.toList;

// #events-by-tag-publisher
class MyEventsByTagJavaPublisher extends AbstractActorPublisher<EventEnvelope> {
  private final Serialization serialization = SerializationExtension.get(getContext().getSystem());

  private final Connection connection;

  private final String tag;

  private final String CONTINUE = "CONTINUE";
  private final int LIMIT = 1000;
  private long currentOffset;
  private List<EventEnvelope> buf = new LinkedList<>();

  private Cancellable continueTask;

  public MyEventsByTagJavaPublisher(
      Connection connection, String tag, Long offset, Duration refreshInterval) {
    this.connection = connection;
    this.tag = tag;
    this.currentOffset = offset;

    final Scheduler scheduler = getContext().getSystem().scheduler();
    this.continueTask =
        scheduler.schedule(
            refreshInterval,
            refreshInterval,
            getSelf(),
            CONTINUE,
            getContext().getDispatcher(),
            getSelf());
  }

  @Override
  public Receive createReceive() {
    return receiveBuilder()
        .matchEquals(
            CONTINUE,
            (in) -> {
              query();
              deliverBuf();
            })
        .match(
            Cancel.class,
            (in) -> {
              getContext().stop(getSelf());
            })
        .build();
  }

  public static Props props(Connection conn, String tag, Long offset, Duration refreshInterval) {
    return Props.create(
        MyEventsByTagJavaPublisher.class,
        () -> new MyEventsByTagJavaPublisher(conn, tag, offset, refreshInterval));
  }

  @Override
  public void postStop() {
    continueTask.cancel();
  }

  private void query() {
    if (buf.isEmpty()) {
      final String query =
          "SELECT id, persistent_repr "
              + "FROM journal WHERE tag = ? AND id > ? "
              + "ORDER BY id LIMIT ?";

      try (PreparedStatement s = connection.prepareStatement(query)) {
        s.setString(1, tag);
        s.setLong(2, currentOffset);
        s.setLong(3, LIMIT);
        try (ResultSet rs = s.executeQuery()) {

          final List<Pair<Long, byte[]>> res = new ArrayList<>(LIMIT);
          while (rs.next()) res.add(Pair.create(rs.getLong(1), rs.getBytes(2)));

          if (!res.isEmpty()) {
            currentOffset = res.get(res.size() - 1).first();
          }

          buf =
              res.stream()
                  .map(
                      in -> {
                        final Long id = in.first();
                        final byte[] bytes = in.second();

                        final PersistentRepr p =
                            serialization.deserialize(bytes, PersistentRepr.class).get();

                        return new EventEnvelope(
                            Offset.sequence(id), p.persistenceId(), p.sequenceNr(), p.payload());
                      })
                  .collect(toList());
        }
      } catch (Exception e) {
        onErrorThenStop(e);
      }
    }
  }

  private void deliverBuf() {
    while (totalDemand() > 0 && !buf.isEmpty()) onNext(buf.remove(0));
  }
}
// #events-by-tag-publisher
