package sample.java;

import se.scalablesolutions.akka.config.ActiveObjectConfigurator;
import static se.scalablesolutions.akka.config.JavaConfig.*;

public class Boot {
  final private ActiveObjectConfigurator manager = new ActiveObjectConfigurator();

  public Boot() throws Exception  {
    manager.configure(
      new RestartStrategy(new OneForOne(), 3, 5000),
        new Component[] {
          new Component(
            sample.java.SimpleService.class,
            new LifeCycle(new Permanent()),
            1000),
          new Component(
            sample.java.PersistentSimpleService.class,
            new LifeCycle(new Permanent()),
            1000)
        }).supervise();
    }
}
