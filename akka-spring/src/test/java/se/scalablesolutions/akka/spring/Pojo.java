package se.scalablesolutions.akka.spring;

import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import javax.annotation.PreDestroy;
import javax.annotation.PostConstruct;

public class Pojo implements PojoInf,ApplicationContextAware {

    private String string;

	private boolean gotApplicationContext = false;
	private boolean preDestroyInvoked = false;
		private boolean postConstructInvoked = false;
	
	public boolean gotApplicationContext() {
		return gotApplicationContext;
	}
	public void setApplicationContext(ApplicationContext context) {
		gotApplicationContext = true;
	}

   public void setString(String s) {
		string = s;
 }

    public String getString() {
		return string;
   }
  
  @PreDestroy
  public void destroy(){
	preDestroyInvoked = true;
  }
	@PostConstruct
	public void create() {
		postConstructInvoked = true;
	}
  

   public boolean isPreDestroyInvoked() {
	  return preDestroyInvoked;
   }

 public boolean isPostConstructInvoked() {
	return postConstructInvoked;
}

 }
