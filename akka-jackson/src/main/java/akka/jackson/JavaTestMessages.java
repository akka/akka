/*
 * Copyright (C) 2016-2018 Lightbend Inc. <https://www.lightbend.com>
 */
package akka.jackson;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;


public interface JavaTestMessages {

  public class SimpleCommand  {
    private final String name;

    // @JsonProperty needed due to single argument constructor, see
    // https://github.com/FasterXML/jackson-modules-java8/tree/master/parameter-names
    public SimpleCommand(@JsonProperty("name") String name) {
      this.name = name;
    }

    public String getName() {
      return name;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) return true;
      if (o == null || getClass() != o.getClass()) return false;
      SimpleCommand that = (SimpleCommand) o;
      return name != null ? name.equals(that.name) : that.name == null;
    }

    @Override
    public int hashCode() {
      return name != null ? name.hashCode() : 0;
    }
  }

  public class SimpleCommand2  {
    public final String name;
    public final String name2;

    // note that no annotation needed here, `javac -parameters` and not single param constructor
    public SimpleCommand2(String name, String name2) {
      this.name = name;
      this.name2 = name2;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) return true;
      if (o == null || getClass() != o.getClass()) return false;

      SimpleCommand2 that = (SimpleCommand2) o;

      if (name != null ? !name.equals(that.name) : that.name != null) return false;
      return name2 != null ? name2.equals(that.name2) : that.name2 == null;
    }

    @Override
    public int hashCode() {
      int result = name != null ? name.hashCode() : 0;
      result = 31 * result + (name2 != null ? name2.hashCode() : 0);
      return result;
    }
  }

  public class OptionalCommand  {
    private final Optional<String> maybe;

    public OptionalCommand(@JsonProperty("maybe") Optional<String> maybe) {
      this.maybe = maybe;
    }

    public Optional<String> getMaybe() {
      return maybe;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) return true;
      if (o == null || getClass() != o.getClass()) return false;

      OptionalCommand that = (OptionalCommand) o;

      return maybe != null ? maybe.equals(that.maybe) : that.maybe == null;
    }

    @Override
    public int hashCode() {
      return maybe != null ? maybe.hashCode() : 0;
    }
  }

  public class BooleanCommand  {
    private final boolean published;

    public BooleanCommand(@JsonProperty("published") boolean published) {
      this.published = published;
    }

    public boolean isPublished() {
      return published;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) return true;
      if (o == null || getClass() != o.getClass()) return false;

      BooleanCommand that = (BooleanCommand) o;

      return published == that.published;
    }

    @Override
    public int hashCode() {
      return (published ? 1 : 0);
    }
  }

  public class CollectionsCommand  {
    private final List<String> strings;
    // if this was List<Object> it would not automatically work,
    // which is good, otherwise arbitrary classes could be loaded
    private final List<SimpleCommand> objects;

    public CollectionsCommand(List<String> strings,
                              List<SimpleCommand> objects) {
      this.strings = strings;
      this.objects = objects;
    }

    public List<String> getStrings() {
      return strings;
    }

    public List<SimpleCommand> getObjects() {
      return objects;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) return true;
      if (o == null || getClass() != o.getClass()) return false;

      CollectionsCommand that = (CollectionsCommand) o;

      if (strings != null ? !strings.equals(that.strings) : that.strings != null) return false;
      return objects != null ? objects.equals(that.objects) : that.objects == null;
    }

    @Override
    public int hashCode() {
      int result = strings != null ? strings.hashCode() : 0;
      result = 31 * result + (objects != null ? objects.hashCode() : 0);
      return result;
    }
  }

  public class TimeCommand {
    public final LocalDateTime timestamp;
    public final Duration duration;

    public TimeCommand(LocalDateTime timestamp, Duration duration) {
      this.timestamp = timestamp;
      this.duration = duration;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) return true;
      if (o == null || getClass() != o.getClass()) return false;

      TimeCommand that = (TimeCommand) o;

      if (timestamp != null ? !timestamp.equals(that.timestamp) : that.timestamp != null) return false;
      return duration != null ? duration.equals(that.duration) : that.duration == null;
    }

    @Override
    public int hashCode() {
      int result = timestamp != null ? timestamp.hashCode() : 0;
      result = 31 * result + (duration != null ? duration.hashCode() : 0);
      return result;
    }
  }

  public class Event1 {

    private final String field1;

    public Event1(String field1) {
      this.field1 = field1;
    }

    public String getField1() {
      return field1;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) return true;
      if (o == null || getClass() != o.getClass()) return false;

      Event1 event1 = (Event1) o;

      return field1 != null ? field1.equals(event1.field1) : event1.field1 == null;
    }

    @Override
    public int hashCode() {
      return field1 != null ? field1.hashCode() : 0;
    }
  }

  public class Event2 {
    private final String field1V2; // renamed from field1
    private final int field2; // new mandatory field

    public Event2(String field1V2, int field2) {
      this.field1V2 = field1V2;
      this.field2 = field2;
    }


    public String getField1V2() {
      return field1V2;
    }

    public int getField2() {
      return field2;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) return true;
      if (o == null || getClass() != o.getClass()) return false;

      Event2 event2 = (Event2) o;

      if (field2 != event2.field2) return false;
      return field1V2 != null ? field1V2.equals(event2.field1V2) : event2.field1V2 == null;
    }

    @Override
    public int hashCode() {
      int result = field1V2 != null ? field1V2.hashCode() : 0;
      result = 31 * result + field2;
      return result;
    }
  }

}
