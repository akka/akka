package sample.java;

import se.scalablesolutions.akka.kernel.config.ActiveObjectManager;
import static se.scalablesolutions.akka.kernel.config.JavaConfig.*;

public class Boot {
  final private ActiveObjectManager manager = new ActiveObjectManager();

  public Boot() throws Exception  {
    manager.configure(
      new RestartStrategy(new OneForOne(), 3, 5000),
        new Component[] {
          new Component(
            sample.java.SimpleService.class,
            new LifeCycle(new Permanent(), 1000),
            1000),
          new Component(
            sample.java.PersistentSimpleService.class,
            new LifeCycle(new Permanent(), 1000),
            1000)
        }).supervise();
    }
}
