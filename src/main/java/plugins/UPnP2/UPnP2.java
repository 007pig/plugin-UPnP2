/*
 * This file is part of UPnP2, a plugin for Freenet.
 *
 * UPnP2 is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 2 of the License, or
 * (at your option) any later version.
 *
 * UPnP2 is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with UPnP2.  If not, see <http://www.gnu.org/licenses/>.
 */

package plugins.UPnP2;

import org.fourthline.cling.UpnpService;
import org.fourthline.cling.UpnpServiceImpl;
import org.fourthline.cling.controlpoint.SubscriptionCallback;
import org.fourthline.cling.model.UnsupportedDataException;
import org.fourthline.cling.model.action.ActionInvocation;
import org.fourthline.cling.model.gena.CancelReason;
import org.fourthline.cling.model.gena.GENASubscription;
import org.fourthline.cling.model.gena.RemoteGENASubscription;
import org.fourthline.cling.model.message.UpnpResponse;
import org.fourthline.cling.model.meta.Device;
import org.fourthline.cling.model.meta.RemoteDevice;
import org.fourthline.cling.model.meta.Service;
import org.fourthline.cling.model.state.StateVariableValue;
import org.fourthline.cling.model.types.UDADeviceType;
import org.fourthline.cling.model.types.UDAServiceType;
import org.fourthline.cling.registry.Registry;
import org.fourthline.cling.support.igd.PortMappingListener;
import org.fourthline.cling.support.igd.callback.PortMappingAdd;
import org.fourthline.cling.support.igd.callback.PortMappingDelete;
import org.fourthline.cling.support.model.PortMapping;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import freenet.pluginmanager.DetectedIP;
import freenet.pluginmanager.ForwardPort;
import freenet.pluginmanager.ForwardPortCallback;
import freenet.pluginmanager.ForwardPortStatus;
import freenet.pluginmanager.FredPlugin;
import freenet.pluginmanager.FredPluginBandwidthIndicator;
import freenet.pluginmanager.FredPluginIPDetector;
import freenet.pluginmanager.FredPluginPortForward;
import freenet.pluginmanager.FredPluginRealVersioned;
import freenet.pluginmanager.FredPluginThreadless;
import freenet.pluginmanager.FredPluginVersioned;
import freenet.pluginmanager.PluginRespirator;
import freenet.support.transport.ip.IPUtil;
import plugins.UPnP2.actions.GetCommonLinkProperties;
import plugins.UPnP2.actions.GetExternalIPSync;
import plugins.UPnP2.actions.GetLinkLayerMaxBitRates;
import plugins.UPnP2.actions.GetSpecificPortMappingEntry;

// TODO: Use Fred's Ticker to replace ScheduledThreadPool
// TODO: Use Fred's Logger instead of System.out.println()
// TODO: Integrate with Gradle Witness: https://github.com/WhisperSystems/gradle-witness

/**
 * Second generation of UPnP plugin for Fred which is based on Cling.
 *
 * @see <a href="http://4thline.org/projects/cling/">Cling - Java/Android UPnP library and tools</a>
 */
public class UPnP2 implements FredPlugin, FredPluginThreadless, FredPluginIPDetector,
        FredPluginPortForward, FredPluginVersioned, FredPluginRealVersioned,
        FredPluginBandwidthIndicator {

    private PluginRespirator pr;

    private UpnpService upnpService = new UpnpServiceImpl();

    /**
     * Store detected External IPs for different services
     */
    private Map<Device, DetectedIP> detectedIPs = new HashMap<>();

    /**
     * Services of type WANIPConnection or WANPPPConnection
     */
    private Set<Service> connectionServices = new HashSet<>();

    /**
     * Services of type WANCommonInterfaceConfig
     */
    private Set<Service> commonServices = new HashSet<>();

    private Map<Service, SubscriptionCallback> subscriptionCallbacks = new HashMap<>();
    private IGDRegistryListener registryListener;

    private boolean booted = false;
    private ScheduledExecutorService portMappingScheduler = null;

    // ###################################
    // FredPlugin method(s)
    // ###################################

    @Override
    public void terminate() {
        System.out.println("UPnP2 plugin ended");

        // Shutdown port mapping scheduler
        portMappingScheduler.shutdownNow();
        try {
            portMappingScheduler.awaitTermination(5, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }

        // Release all resources and advertise BYEBYE to other UPnP devices
        upnpService.shutdown();
    }

    @Override
    public void runPlugin(PluginRespirator pr) {
        this.pr = pr;

        System.out.println("UPnP2 plugin started");

        // This will create necessary network resources for UPnP right away
        System.out.println("Starting Cling...");

        // Add listeners for upnpService
        registryListener = new IGDRegistryListener();
        upnpService.getRegistry().addListener(registryListener);

        // Send a search message to all devices and services, they should respond soon
        upnpService.getControlPoint().search();

    }

    // ###################################
    // FredPluginIPDetector method(s)
    // ###################################

    @Override
    public DetectedIP[] getAddress() {
        System.out.println("Calling getAddress()");

        waitForBooting();

        if (connectionServices.size() == 0) {
            return null;
        }

        if (detectedIPs.size() > 0) {
            // IP is found from Service event callback
            return detectedIPs.values().toArray(new DetectedIP[detectedIPs.size()]);
        } else {
            // Maybe the event is not fired for some reason. We need to request the IP manually.
            getExternalIP();

            if (detectedIPs.size() > 0) {
                return detectedIPs.values().toArray(new DetectedIP[detectedIPs.size()]);
            } else {
                return null;
            }
        }

    }

    // ###################################
    // FredPluginPortForward method(s)
    // ###################################

    @Override
    public void onChangePublicPorts(final Set<ForwardPort> ports, final ForwardPortCallback cb) {
        System.out.println("Calling onChangePublicPorts()");

        waitForBooting();

        if (portMappingScheduler != null) {
            // onChangePublicPorts() is called again
            // We need to setup a new Scheduler pool
            portMappingScheduler.shutdownNow();
            try {
                portMappingScheduler.awaitTermination(5, TimeUnit.MINUTES);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }
        portMappingScheduler = Executors.newScheduledThreadPool(1);

        // Run every 5 minutes to keep port mapped
        portMappingScheduler.scheduleAtFixedRate(new Runnable() {
            @Override
            public void run() {
                if (connectionServices.size() > 0) {

                    Set<PortMapping> portMappings = new HashSet<>();
                    Map<PortMapping, ForwardPort> forwardPortMap = new HashMap<>();
                    for (Service connectionService : connectionServices) {
                        for (ForwardPort port : ports) {

                            PortMapping.Protocol protocol;
                            String protocolName;
                            switch (port.protocol) {
                                case ForwardPort.PROTOCOL_UDP_IPV4:
                                    protocol = PortMapping.Protocol.UDP;
                                    protocolName = "UDP";
                                    break;
                                case ForwardPort.PROTOCOL_TCP_IPV4:
                                    protocol = PortMapping.Protocol.TCP;
                                    protocolName = "TCP";
                                    break;
                                default:
                                    protocol = PortMapping.Protocol.UDP;
                                    protocolName = "UDP";
                            }

                            System.out.printf("Mapping port: %s %d (%s)%n", protocolName, port
                                            .portNumber,
                                    port.name);

                            // Each service has its own local IP
                            String localIP = ((RemoteDevice) connectionService.getDevice())
                                    .getIdentity()
                                    .getDiscoveredOnLocalAddress().getHostAddress();


                            System.out.println("For device: " + connectionService.getDevice());
                            System.out.println("For service: " + connectionService);
                            System.out.println("For local IP: " + localIP);

                            PortMapping portMapping = new PortMapping(
                                    port.portNumber,
                                    localIP,
                                    protocol,
                                    "Freenet 0.7 " + port.name
                            );


                            // Mapping for each local IP
                            portMappings.add(portMapping);

                            forwardPortMap.put(portMapping, port);
                        }

                        // Add this port's mappings for this service
                        registryListener.addPortMappings(connectionService, portMappings,
                                forwardPortMap,
                                cb);
                        // Clear portMappings and get ready for next action
                        portMappings.clear();
                        forwardPortMap.clear();

                    }
                } else {
                    System.out.println("Unable to get localIPs.");
                }
            }
        }, 0, 5, TimeUnit.MINUTES);

    }

    // ###################################
    // FredPluginRealVersioned method(s)
    // ###################################

    @Override
    public long getRealVersion() {
        return 5;
    }

    // ###################################
    // FredPluginVersioned method(s)
    // ###################################

    @Override
    public String getVersion() {
        return "1.0.0";
    }

    // ###################################
    // FredPluginBandwidthIndicator method(s)
    // ###################################

    @Override
    public int getUpstramMaxBitRate() {
        System.out.println("Calling getUpstramMaxBitRate()");

        waitForBooting();

        if (connectionServices.size() < 0 && commonServices.size() < 0) {
            return -1;
        }

        int[] rates = getRates();


        if (rates == null) {
            return -1;
        }

        System.out.println("Upstream MaxBitRate: " + rates[0]);

        return rates[0];
    }

    @Override
    public int getDownstreamMaxBitRate() {
        System.out.println("Calling getDownstreamMaxBitRate()");

        waitForBooting();

        if (connectionServices.size() < 0 && commonServices.size() < 0) {
            return -1;
        }

        int[] rates = getRates();

        if (rates == null) {
            return -1;
        }

        System.out.println("Downstream MaxBitRate: " + rates[0]);

        return rates[1];
    }

    // ###################################
    // Implementations
    // ###################################

    synchronized private void waitForBooting() {
        // If no connection services available and it's the first call,
        // we retry 10 times for the plugin to get enough IGDs
        if (!booted) {
            for (int count = 0; count < 10; count++) {
                if (connectionServices.size() == 0) {
                    // No devices found yet
                    try {
                        Thread.sleep(1000);
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                } else {
                    // Devices found. Wait for 5 more seconds for more devices
                    try {
                        Thread.sleep(5000);
                    } catch (InterruptedException e) {
                        e.printStackTrace();
                    }
                    break;
                }
                // Whatever any devices found, we won't sleep any more. If new devices later
                // added, Cling will automatically subscribe the service's event and report IPs
            }
            booted = true;
        }

    }

    /**
     * Actively request external IP addresses. This method blocks.
     */
    private void getExternalIP() {

        if (connectionServices.size() == 0) {
            System.out.println("No internet gateway device detected. Unable to get external " +
                    "address.");
            return;
        }

        System.out.println("Try to get external IP");

        for (Service connectionService : connectionServices) {

            new GetExternalIPSync(connectionService, upnpService.getControlPoint()) {

                @Override
                protected void success(String externalIPAddress) {
                    try {
                        System.out.println("Get external IP: " + externalIPAddress);

                        InetAddress inetAddress = InetAddress.getByName
                                (externalIPAddress);
                        if (IPUtil.isValidAddress(inetAddress, false)) {
                            detectedIPs.put(getActionInvocation().getAction()
                                            .getService().getDevice().getRoot(),
                                    new DetectedIP(inetAddress,
                                            DetectedIP.NOT_SUPPORTED));
                        }

                    } catch (UnknownHostException e) {
                        e.printStackTrace();
                    }
                }

                @Override
                public void failure(ActionInvocation invocation,
                                    UpnpResponse operation,
                                    String defaultMsg) {
                    System.out.println("Unable to get external IP. Reason: " +
                            defaultMsg);
                }
            }.run(); // Synchronous!

        }
    }

    private int[] getRates() {

        final List<Integer> upRates = new ArrayList<>();
        final List<Integer> downRates = new ArrayList<>();

        for (final Service service : connectionServices) {
            System.out.println("Service Type: " + service.getServiceType().getType());
            if (service.getServiceType().getType().equals("WANPPPConnection")
                    // Make sure the device isn't double natted
                    // Double natted devices won't have a valid external IP
                    && detectedIPs.containsKey(service.getDevice().getRoot())
                    ) {

                new GetLinkLayerMaxBitRates(service, upnpService.getControlPoint()) {
                    @Override
                    protected void success(int newUpstreamMaxBitRate, int newDownstreamMaxBitRate) {
                        System.out.println("newUpstreamMaxBitRate: " + newUpstreamMaxBitRate);
                        System.out.println("newDownstreamMaxBitRate: " + newDownstreamMaxBitRate);

                        upRates.add(newUpstreamMaxBitRate);
                        downRates.add(newDownstreamMaxBitRate);
                    }

                    @Override
                    public void failure(ActionInvocation invocation, UpnpResponse operation,
                                        String defaultMsg) {
                        System.out.println("Unable to get MaxBitRates. Reason: " +
                                defaultMsg);
                    }
                }.run(); // Synchronous!

            }
        }

        if (upRates.size() > 0) {
            int upRatesSum = 0;
            for (int rate : upRates) {
                upRatesSum += rate;
            }
            int downRatesSum = 0;
            for (int rate : downRates) {
                downRatesSum += rate;
            }
            return new int[]{upRatesSum, downRatesSum};
        }

        // We get nothing from GetLinkLayerMaxBitRates. Try GetCommonLinkProperties

        final List<Integer> upRates2 = new ArrayList<>();
        final List<Integer> downRates2 = new ArrayList<>();

        for (final Service service : commonServices) {
            System.out.println("Service Type: " + service.getServiceType().getType());

            // Make sure the device isn't double natted
            // Double natted devices won't have a valid external IP
            if (detectedIPs.containsKey(service.getDevice().getRoot())) {
                new GetCommonLinkProperties(service, upnpService.getControlPoint()) {
                    @Override
                    protected void success(int newUpstreamMaxBitRate, int newDownstreamMaxBitRate) {
                        System.out.println("newUpstreamMaxBitRate: " + newUpstreamMaxBitRate);
                        System.out.println("newDownstreamMaxBitRate: " + newDownstreamMaxBitRate);

                        upRates2.add(newUpstreamMaxBitRate);
                        downRates2.add(newDownstreamMaxBitRate);
                    }

                    @Override
                    public void failure(ActionInvocation invocation, UpnpResponse operation,
                                        String defaultMsg) {
                        System.out.println("Unable to get GetCommonLinkProperties. Reason: " +
                                defaultMsg);
                    }
                }.run(); // Synchronous!
            }
        }

        if (upRates2.size() > 0) {
            int upRatesSum = 0;
            for (int rate : upRates2) {
                upRatesSum += rate;
            }
            int downRatesSum = 0;
            for (int rate : downRates2) {
                downRatesSum += rate;
            }
            return new int[]{upRatesSum, downRatesSum};
        }

        return null;
    }

    /**
     * Registry Listener for InternetGatewayDevice
     */
    private class IGDRegistryListener extends PortMappingListener {

        public IGDRegistryListener() {
            super(new PortMapping[0]);
        }

        @Override
        synchronized public void deviceAdded(Registry registry, Device device) {

            System.out.println("Remote device available: " + device.getDisplayString());

            Service commonService;
            if ((commonService = discoverCommonService(device)) == null) return;

            commonServices.add(commonService);

            Service connectionService;
            if ((connectionService = discoverConnectionService(device)) == null) return;

            connectionServices.add(connectionService);

            // Add service events listener
            SubscriptionCallback callback = new IDGSubscriptionCallback(connectionService);
            upnpService.getControlPoint().execute(callback);
            subscriptionCallbacks.put(connectionService, callback);

        }

        @Override
        synchronized public void deviceRemoved(Registry registry, Device device) {

            System.out.println("Remote device unavailable: " + device.getDisplayString());

            super.deviceRemoved(registry, device);

            for (Service service : device.findServices()) {
                // End the subscription
                SubscriptionCallback callback = subscriptionCallbacks.get(service);

                if (callback != null) {
                    callback.end();

                    if (callback.getSubscription() instanceof RemoteGENASubscription) {
                        // Remove subscription from registry
                        upnpService.getRegistry().removeRemoteSubscription(
                                (RemoteGENASubscription) callback.getSubscription());
                    }

                    // Remove subscription callback
                    subscriptionCallbacks.remove(service);
                }
                // Remove Services
                connectionServices.remove(service);
            }

            // Clear detected IPs
            detectedIPs.clear();

        }

        synchronized public void removeAllPortMappings() {

            final CountDownLatch latch = new CountDownLatch(activePortMappings.size());

            // Unmap old ports
            for (Map.Entry<Service, List<PortMapping>> activeEntry : activePortMappings.entrySet
                    ()) {

                final Iterator<PortMapping> it = activeEntry.getValue().iterator();
                while (it.hasNext()) {
                    final PortMapping pm = it.next();
                    System.out.println("Trying to delete port mapping on IGD: " + pm);
                    new PortMappingDelete(activeEntry.getKey(), upnpService.getControlPoint(), pm) {

                        @Override
                        public void success(ActionInvocation invocation) {
                            System.out.println("Port mapping deleted: " + pm);
                            it.remove();
                            latch.countDown();
                        }

                        @Override
                        public void failure(ActionInvocation invocation, UpnpResponse operation,
                                            String defaultMsg) {
                            handleFailureMessage("Failed to delete port mapping: " + pm);
                            handleFailureMessage("Reason: " + defaultMsg);
                            latch.countDown();
                        }

                    }.run(); // Synchronous!
                }
            }

            try {
                latch.await();
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            activePortMappings = new HashMap<>();
        }

        synchronized public void addPortMappings(final Service connectionService, Set<PortMapping>
                newPortMappings, final Map<PortMapping, ForwardPort> forwardPortMap, final
                                                 ForwardPortCallback cb) {

            if (connectionService == null || newPortMappings.size() == 0) return;

            System.out.println("Activating port mappings on: " + connectionService);

            final List<PortMapping> activeForService = new ArrayList<>();
            for (final PortMapping pm : newPortMappings) {

                System.out.println("Checking if the Port is already Mapped: " + pm);

                new GetSpecificPortMappingEntry(connectionService, upnpService.getControlPoint(),
                        pm) {
                    @Override
                    public void success(ActionInvocation invocation) {
                        System.out.println("Port is already Mapped: " + pm);
                    }

                    @Override
                    public void failure(ActionInvocation invocation, UpnpResponse operation, String defaultMsg) {
                        System.out.println("Port is not Mapped: " + pm);
                        System.out.println("Adding Port Mapping: " + pm);

                        final ForwardPort forwardPort = forwardPortMap.get(pm);

                        new PortMappingAdd(connectionService, upnpService.getControlPoint(), pm) {

                            @Override
                            public void success(ActionInvocation invocation) {
                                System.out.println("Port mapping added: " + pm);
                                activeForService.add(pm);

                                // Notify Fred the port mapping is successful
                                ForwardPortStatus status = new ForwardPortStatus(ForwardPortStatus
                                        .MAYBE_SUCCESS, "", pm.getExternalPort().getValue().intValue());

                                Map<ForwardPort, ForwardPortStatus> statuses = new HashMap<>();
                                statuses.put(forwardPort, status);

                                cb.portForwardStatus(statuses);
                            }

                            @Override
                            public void failure(ActionInvocation invocation, UpnpResponse operation,
                                                String defaultMsg) {
                                handleFailureMessage("Failed to add port mapping: " + pm);
                                handleFailureMessage("Reason: " + defaultMsg);

                                // Notify Fred the port mapping is failed
                                ForwardPortStatus status = new ForwardPortStatus(ForwardPortStatus
                                        .DEFINITE_FAILURE, defaultMsg, forwardPort.portNumber);

                                Map<ForwardPort, ForwardPortStatus> statuses = new HashMap<>();
                                statuses.put(forwardPort, status);

                                cb.portForwardStatus(statuses);
                            }
                        }.run(); // Synchronous!
                    }
                }.run(); // Synchronous!

                activePortMappings.put(connectionService, activeForService);

            }

        }

        protected Service discoverCommonService(Device device) {
            if (!device.getType().equals(IGD_DEVICE_TYPE)) {
                return null;
            }

            UDADeviceType wanDeviceType = new UDADeviceType("WANDevice");
            Device[] wanDevices = device.findDevices(wanDeviceType);
            if (wanDevices.length == 0) {
                System.out.println("IGD doesn't support '" + wanDeviceType + "': " + device);
                return null;
            }

            Device wanDevice = wanDevices[0];
            System.out.println("Using first discovered WAN device: " + wanDevice);

            return wanDevice.findService(new UDAServiceType("WANCommonInterfaceConfig"));
        }


    }

    private class IDGSubscriptionCallback extends SubscriptionCallback {

        private int renewalFailedCount = 0;

        public IDGSubscriptionCallback(Service connectionService) {
            super(connectionService, 600);
        }

        @Override
        public void established(GENASubscription sub) {
            System.out.println("Established: " + sub.getSubscriptionId());
        }

        @Override
        protected void failed(GENASubscription subscription,
                              UpnpResponse responseStatus,
                              Exception exception,
                              String defaultMsg) {
            System.err.println("Failed: " + defaultMsg);
        }

        @Override
        public void ended(GENASubscription sub,
                          CancelReason reason,
                          UpnpResponse response) {
            System.err.println("Ended: " + reason);
            System.err.println("Response: " + response);
            if (reason == CancelReason.RENEWAL_FAILED) {
                renewalFailedCount++;

                if (renewalFailedCount == 5) {
                    // Some routers doesn't response correct header and Cling won't be able
                    // to renew it. Then we need to remove and re-subscribe.

                    System.err.println("Renewal failed. Try to re-subscribe.");

                    // Remove current subscription from registry
                    upnpService.getRegistry().removeRemoteSubscription((RemoteGENASubscription)
                            sub);

                    SubscriptionCallback callback = new IDGSubscriptionCallback(service);
                    upnpService.getControlPoint().execute(callback);
                    subscriptionCallbacks.put(service, callback);
                }

            }
        }

        @Override
        public void eventReceived(GENASubscription sub) {

            Map values = sub.getCurrentValues();

            System.out.println(values);

            StateVariableValue externalIPAddress = (StateVariableValue) values.get
                    ("ExternalIPAddress");

            try {
                InetAddress inetAddress = InetAddress.getByName
                        (externalIPAddress.toString());
                if (IPUtil.isValidAddress(inetAddress, false)) {
                    DetectedIP detectedIP = new DetectedIP(inetAddress, DetectedIP.NOT_SUPPORTED);
                    if (!detectedIPs.values().contains(detectedIP)) {
                        System.out.println("New External IP found: " + externalIPAddress
                                .toString());
                        System.out.println("For device: " + sub.getService().getDevice().getRoot
                                ().getDisplayString());
                        detectedIPs.put(sub.getService().getDevice().getRoot(), detectedIP);
                    }
                }
                // If the IP address is already got, the next call to getAddress() won't
                // need to be blocked.
                booted = true;
            } catch (UnknownHostException e) {
                e.printStackTrace();
            }
        }

        @Override
        public void eventsMissed(GENASubscription sub, int numberOfMissedEvents) {
            System.out.println("Missed events: " + numberOfMissedEvents);
        }

        @Override
        protected void invalidMessage(RemoteGENASubscription sub,
                                      UnsupportedDataException ex) {
            // Log/send an error report?
        }
    }
}
