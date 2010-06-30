package se.scalablesolutions.akka.actor;

import se.scalablesolutions.akka.actor.annotation.prerestart;
import se.scalablesolutions.akka.actor.annotation.postrestart;

public class SimpleJavaPojo {
        
        public boolean pre = false;
        public boolean post = false;
        
        private String name;
        
        public void setName(String name) {
                this.name = name;
        }
        
        public String getName() {
                return name;
        }
        
        @prerestart
        public void pre() {
                System.out.println("** pre()");
                pre = true;
        }
        
        @postrestart
        public void post() {
                System.out.println("** post()");
                post = true;
        }

        public void throwException() {
                throw new RuntimeException();
        }
}
