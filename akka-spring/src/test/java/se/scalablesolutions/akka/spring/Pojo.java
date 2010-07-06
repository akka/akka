package se.scalablesolutions.akka.spring;

import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;

public class Pojo implements PojoInf,ApplicationContextAware {

    private String string;

	private boolean gotApplicationContext = false;
	
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
    
 }
