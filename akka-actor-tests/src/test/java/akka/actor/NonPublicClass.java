package akka.actor;

import com.sun.xml.internal.ws.api.PropertySet;

/**
 * Created by IntelliJ IDEA.
 * User: viktorklang
 * Date: 6/13/12
 * Time: 12:12 PM
 * To change this template use File | Settings | File Templates.
 */
public class NonPublicClass {
    public static Props createProps() {
        return new Props(MyNonPublicActorClass.class);
    }
}

class MyNonPublicActorClass extends UntypedActor {
    @Override public void onReceive(Object msg) {
        getSender().tell(msg);
    }
}