package akka.event;

import akka.actor.UntypedActor;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public class ActorWithMDC extends UntypedActor {

    private final DiagnosticLoggingAdapter logger = Logging.getLogger(this);

    @Override
    public void onReceive(Object message) throws Exception {
        Log log = (Log) message;

        Map<String, Object> mdc;
        if(log.message.startsWith("No MDC")) {
            mdc = Collections.emptyMap();
        } else if(log.message.equals("Null MDC")) {
            mdc = null;
        } else {
            mdc = new LinkedHashMap<String, Object>();
            mdc.put("messageLength", log.message.length());
        }
        logger.setMDC(mdc);

        switch (log.level()) {
            case 1:
                logger.error(log.message);
                break;
            case 2:
                logger.warning(log.message);
                break;
            case 3:
                logger.info(log.message);
                break;
            default:
                logger.debug(log.message);
                break;
        }

        logger.clearMDC();
    }

    public static class Log {
        private final Object level;
        public final String message;

        public Log(Object level, String message) {
            this.level = level;
            this.message = message;
        }

        public int level() {
            return (Integer) this.level;
        }
    }


}
