package se.scalablesolutions.akka.spring.foo;

public class MyPojo {
        
        private String foo;
        private String bar;
        
        
        public MyPojo() {
                this.foo = "foo";
                this.bar = "bar";
        }


        public String getFoo() {
                return foo;
        }


        public String getBar() {
                return bar;
        }
        
        public void preRestart() {
                System.out.println("pre restart");
        }
        
        public void postRestart() {
                System.out.println("post restart");
        }

    public String longRunning() {
      try {
          Thread.sleep(6000);
      } catch (InterruptedException e) {
      }
      return "this took long";
    }

}
