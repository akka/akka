/*
 * Copyright (C) 2016-2017 Lightbend Inc. <http://www.lightbend.com>
 */

package docs.stream;

import akka.actor.ActorSystem;
import akka.stream.Attributes;
import akka.stream.Materializer;
import akka.stream.Outlet;
import akka.stream.SourceShape;
import akka.stream.stage.*;
import docs.AbstractJavaTest;
import org.junit.Test;

import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collector;

public class GraphStageLoggingDocTest extends AbstractJavaTest {
  static ActorSystem system;
  static Materializer mat;

  @Test
  public void compileOnlyTestClass() throws Exception { }
  
  //#stage-with-logging
  public class RandomLettersSource extends GraphStage<SourceShape<String>> {
    public final Outlet<String> out = Outlet.create("RandomLettersSource.in");

    private final SourceShape<String> shape = SourceShape.of(out);

    @Override
    public SourceShape<String> shape() {
      return shape;
    }

    @Override
    public GraphStageLogic createLogic(Attributes inheritedAttributes) {
      return new GraphStageLogicWithLogging(shape()) {

        {
          setHandler(out, new AbstractOutHandler() {
            @Override
            public void onPull() throws Exception {
              final String s = nextChar();// ASCII lower case letters

              // `log` is obtained from materializer automatically (via StageLogging)
              log().debug("Randomly generated: [{}]", s);

              push(out, s);
            }

            private String nextChar() {
              final char i = (char) ThreadLocalRandom.current().nextInt('a', 'z' + 1);
              return String.valueOf(i);
            }

          });
        }
      };
    }
  }
  //#stage-with-logging

}
