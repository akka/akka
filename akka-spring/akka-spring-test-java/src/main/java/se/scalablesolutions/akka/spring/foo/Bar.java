package se.scalablesolutions.akka.spring.foo;

import java.io.IOException;

public class Bar implements IBar {

        @Override
        public String getBar() {
                return "bar";
        }
        
        public void throwsIOException() throws IOException {
          throw new IOException("some IO went wrong");
        }

}
