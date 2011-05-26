package akka.spring;

import javax.annotation.PreDestroy;
import javax.annotation.PostConstruct;

public interface PojoInf {

  public String getStringFromVal();
  public String getStringFromRef();
  public boolean gotApplicationContext();
  public boolean isPreStartInvoked();

}
