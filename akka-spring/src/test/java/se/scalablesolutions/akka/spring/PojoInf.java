package se.scalablesolutions.akka.spring;

import javax.annotation.PreDestroy;
import javax.annotation.PostConstruct;

public interface PojoInf {

    public String getString();
    public boolean gotApplicationContext();
    public boolean isPreDestroyInvoked();
    public boolean isPostConstructInvoked();
	
	@PreDestroy
	public void destroy();
	
	@PostConstruct
	public void create();
 }
