package plugins.UPnP2;

import org.fourthline.cling.UpnpService;
import org.fourthline.cling.controlpoint.SubscriptionCallback;
import org.fourthline.cling.model.UnsupportedDataException;
import org.fourthline.cling.model.gena.CancelReason;
import org.fourthline.cling.model.gena.GENASubscription;
import org.fourthline.cling.model.gena.RemoteGENASubscription;
import org.fourthline.cling.model.message.UpnpResponse;
import org.fourthline.cling.model.meta.Service;
import org.fourthline.cling.model.state.StateVariableValue;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Map;

import freenet.pluginmanager.DetectedIP;
import freenet.support.LogThresholdCallback;
import freenet.support.Logger;
import freenet.support.transport.ip.IPUtil;

/**
 * Created by xiaoyu on 1/14/16. 2
 */
class IGDSubscriptionCallback extends SubscriptionCallback {

    private static volatile boolean logMINOR;

    static {
        Logger.registerLogThresholdCallback(new LogThresholdCallback() {
            @Override
            public void shouldUpdate() {
                logMINOR = Logger.shouldLog(Logger.LogLevel.MINOR, this);
            }
        });
    }

    private ServiceManager serviceManager;
    private int renewalFailedCount = 0;

    public IGDSubscriptionCallback(Service connectionService, ServiceManager serviceManager) {
        super(connectionService, 600);
        this.serviceManager = serviceManager;
    }

    @Override
    public void established(GENASubscription sub) {
        Logger.normal(this, "GENA Established: " + sub.getSubscriptionId());
    }

    @Override
    protected void failed(GENASubscription subscription,
                          UpnpResponse responseStatus,
                          Exception exception,
                          String defaultMsg) {
        Logger.warning(this, "GENA Failed: " + defaultMsg);
    }

    @Override
    public void ended(GENASubscription sub,
                      CancelReason reason,
                      UpnpResponse response) {
        Logger.normal(this, "GENA Ended: " + reason);
        if (logMINOR) Logger.minor(this, "GENA Response: " + response);
        if (reason == CancelReason.RENEWAL_FAILED) {
            renewalFailedCount++;

            if (renewalFailedCount == 5) {
                // Some routers doesn't response correct header and Cling won't be able
                // to renew it. Then we need to remove and re-subscribe.

                Logger.warning(this, "Renewal failed. Try to re-subscribe.");

                UpnpService upnpService = serviceManager.getUpnpService();

                // Remove current subscription from registry
                upnpService.getRegistry().removeRemoteSubscription((RemoteGENASubscription)
                        sub);

                SubscriptionCallback callback = new IGDSubscriptionCallback(service, serviceManager);
                upnpService.getControlPoint().execute(callback);
                serviceManager.addSubscriptionCallback(service, callback);
            }

        }
    }

    @Override
    public void eventReceived(GENASubscription sub) {

        Map values = sub.getCurrentValues();

        System.out.println(values);

        StateVariableValue externalIPAddress =
                (StateVariableValue) values.get("ExternalIPAddress");

        try {
            InetAddress inetAddress = InetAddress.getByName
                    (externalIPAddress.toString());
            if (IPUtil.isValidAddress(inetAddress, false)) {
                DetectedIP detectedIP = new DetectedIP(inetAddress, DetectedIP.NOT_SUPPORTED);
                if (!serviceManager.getDetectedIPs().values().contains(detectedIP)) {
                    Logger.normal(this, "New External IP found: " + externalIPAddress
                            .toString());
                    Logger.normal(this, "For device: " +
                            sub.getService().getDevice().getRoot().getDisplayString());
                    serviceManager.addDetectedIP(sub.getService().getDevice().getRoot(), detectedIP);
                }
            }
            // If the IP address is already got, the next call to getAddress() won't
            // need to be blocked.
            serviceManager.setBooted(true);
        } catch (UnknownHostException e) {
            e.printStackTrace();
        }
    }

    @Override
    public void eventsMissed(GENASubscription sub, int numberOfMissedEvents) {
        Logger.warning(this, "Missed events: " + numberOfMissedEvents);
    }

    @Override
    protected void invalidMessage(RemoteGENASubscription sub,
                                  UnsupportedDataException ex) {
        // Log/send an error report?
    }

}
