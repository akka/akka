package se.scalablesolutions.akka.spring;

import javax.annotation.PreDestroy;
import javax.annotation.PostConstruct;

public interface PojoInf {

  public String getString();
  public boolean gotApplicationContext();
  public boolean isPostConstructInvoked();

  @PostConstruct
  public void create();
}
