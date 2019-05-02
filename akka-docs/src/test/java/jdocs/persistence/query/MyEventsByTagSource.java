package jdocs.persistence.query;

import akka.actor.ActorSystem;
import akka.japi.Pair;
import akka.persistence.PersistentRepr;
import akka.persistence.query.EventEnvelope;
import akka.persistence.query.Offset;
import akka.serialization.Serialization;
import akka.serialization.SerializationExtension;
import akka.stream.*;
import akka.stream.stage.GraphStage;
import akka.stream.stage.GraphStageLogic;
import akka.stream.stage.OutHandler;
import akka.stream.stage.TimerGraphStageLogic;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;

import static java.util.stream.Collectors.toList;

// #events-by-tag-publisher
public class MyEventsByTagSource extends GraphStage<SourceShape<EventEnvelope>> {
   private Outlet<EventEnvelope> out = Outlet.create("MyEventByTagSource.out");
   private static final String CONTINUE = "CONTINUE";
   private static final int LIMIT = 1000;
   private final Connection connection;
   private final String tag;
   private final long initialOffset;
   private final Duration refreshInterval;

   public MyEventsByTagSource(Connection connection, String tag, long initialOffset, Duration refreshInterval) {
      this.connection = connection;
      this.tag = tag;
      this.initialOffset = initialOffset;
      this.refreshInterval = refreshInterval;
   }

   @Override
   public SourceShape<EventEnvelope> shape() {
      return SourceShape.of(out);
   }

   class Logic extends TimerGraphStageLogic implements OutHandler {

      private ActorSystem system = ((ActorMaterializer) materializer()).system();
      private long currentOffset = initialOffset;
      private List<EventEnvelope> buf = new LinkedList<>();
      private final Serialization serialization = SerializationExtension.get(system);

      Logic() {
         super(shape());
      }

      @Override
      public void preStart() {
         schedulePeriodically(CONTINUE, refreshInterval);
      }

      @Override
      public void onPull() {
         query();
         deliver();
      }

      @Override
      public void onTimer(Object timerKey) {
         query();
         deliver();
      }

      private void deliver() {
         if (isAvailable(out) && !buf.isEmpty()) {
           push(out, buf.remove(0));
         }
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
               failStage(e);
            }
         }
      }
   }
   @Override
   public GraphStageLogic createLogic(Attributes inheritedAttributes) {
      return new Logic();
   }

}
// #events-by-tag-publisher
