package plugins.UPnP2;

import java.util.Set;
import java.util.concurrent.TimeUnit;

import freenet.pluginmanager.ForwardPort;
import freenet.pluginmanager.ForwardPortCallback;
import freenet.support.Ticker;

/**
 * Created by xiaoyu on 1/18/16. 2
 */
public class PortMappingTicker {

    private ServiceManager serviceManager;
    private Ticker ticker;
    private Set<ForwardPort> ports;
    private ForwardPortCallback cb;
    private Runnable portMappingRunnable = new Runnable() {
        @Override
        public void run() {
            serviceManager.addPortMappings(ports, cb);
        }
    };
    private boolean started = false;

    PortMappingTicker(ServiceManager serviceManager, Ticker ticker) {
        this.serviceManager = serviceManager;
        this.ticker = ticker;
    }

    public void startPortMapping(Set<ForwardPort> ports, ForwardPortCallback cb) {

        serviceManager.waitForBooting();

        synchronized (this) {
            if (started) {
                if (ports.equals(this.ports) && cb.equals(this.cb)) {
                    // Nothing changes. Just return.
                    return;
                }

                // Something changed. Remove old port mappings
                serviceManager.removeAllPortMappings();
            }

            started = true;

            this.ports = ports;
            this.cb = cb;
        }

        realDoPortMapping();

    }

    public void stopPortMapping() {
        if (!started) return;

        ticker.removeQueuedJob(portMappingRunnable);
    }

    private void realDoPortMapping() {

        serviceManager.addPortMappings(ports, cb);

        long now = System.currentTimeMillis();
        ticker.queueTimedJob(portMappingRunnable, "portMappingRunnable" + now,
                TimeUnit.MINUTES.toMillis(5), false, false);

    }

}
