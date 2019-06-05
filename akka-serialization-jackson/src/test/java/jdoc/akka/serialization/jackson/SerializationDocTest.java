/*
 * Copyright (C) 2019 Lightbend Inc. <https://www.lightbend.com>
 */

package jdoc.akka.serialization.jackson;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;

public class SerializationDocTest {

  interface OneConstructorParamExample1 {

    // #one-constructor-param-1
    public class SimpleCommand implements MySerializable {
      private final String name;

      public SimpleCommand(String name) {
        this.name = name;
      }
    }
    // #one-constructor-param-1
  }

  interface OneConstructorParamExample2 {
    // #one-constructor-param-2
    public class SimpleCommand implements MySerializable {
      private final String name;

      @JsonCreator
      public SimpleCommand(String name) {
        this.name = name;
      }
    }
    // #one-constructor-param-2
  }

  interface OneConstructorParamExample3 {
    // #one-constructor-param-3
    public class SimpleCommand implements MySerializable {
      private final String name;

      public SimpleCommand(@JsonProperty("name") String name) {
        this.name = name;
      }
    }
    // #one-constructor-param-3
  }
}
