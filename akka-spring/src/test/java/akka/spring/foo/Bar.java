package akka.spring.foo;

import java.io.IOException;
import akka.actor.*;

public class Bar extends TypedActor implements IBar {

        @Override
        public String getBar() {
                return "bar";
        }

        public void throwsIOException() throws IOException {
          throw new IOException("some IO went wrong");
        }

}
