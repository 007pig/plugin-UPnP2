package plugins.UPnP2;

import org.fourthline.cling.UpnpService;
import org.fourthline.cling.UpnpServiceImpl;
import org.fourthline.cling.model.meta.Service;

import java.util.HashSet;
import java.util.Set;

import freenet.support.Logger;

/**
 * Manage UPnP Services.
 * Singleton.
 */
class UPnPServiceManager {

    private static volatile UPnPServiceManager instance = null;

    /**
     * Cling Core UPnP stack
     */
    private UpnpService upnpService = new UpnpServiceImpl();

    /**
     * Services of type WANIPConnection or WANPPPConnection
     */
    private Set<Service> connectionServices = new HashSet<>();

    /**
     * Services of type WANCommonInterfaceConfig
     */
    private Set<Service> commonServices = new HashSet<>();

    /**
     * Private Ctor
     */
    private UPnPServiceManager() {

    }

    public static UPnPServiceManager getInstance() {
        if (instance == null) {
            synchronized (UPnPServiceManager.class) {
                if (instance == null) {
                    instance = new UPnPServiceManager();
                }
            }
        }
        return instance;
    }

    /**
     * Initialize service manager
     */
    public void init() {
        // This will create necessary network resources for UPnP right away
        Logger.normal(this, "Starting Cling...");

        // Add listeners for upnpService
        registryListener = new IGDRegistryListener(upnpService);
        upnpService.getRegistry().addListener(registryListener);

        // Send a search message to all devices and services, they should respond soon
        upnpService.getControlPoint().search();

    }

    public UpnpService getUpnpService() {
        return upnpService;
    }

    public Set<Service> getConnectionServices() {
        return connectionServices;
    }

    public void addConnectionService(Service connectionService) {
        connectionServices.add(connectionService);
    }

    public void removeConnectionService(Service connectionService) {
        connectionServices.remove(connectionService);
    }

    public Set<Service> getCommonServices() {
        return commonServices;
    }

    public void addCommonService(Service commonService) {
        commonServices.add(commonService);
    }

    public void removeCommonService(Service commonService) {
        commonServices.remove(commonService);
    }



}
