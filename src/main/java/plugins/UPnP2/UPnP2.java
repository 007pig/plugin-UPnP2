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
import org.fourthline.cling.model.action.ActionInvocation;
import org.fourthline.cling.model.message.UpnpResponse;
import org.fourthline.cling.model.meta.Device;
import org.fourthline.cling.model.meta.RemoteDevice;
import org.fourthline.cling.model.meta.Service;
import org.fourthline.cling.registry.Registry;
import org.fourthline.cling.support.igd.PortMappingListener;
import org.fourthline.cling.support.igd.callback.GetExternalIP;
import org.fourthline.cling.support.igd.callback.PortMappingAdd;
import org.fourthline.cling.support.model.PortMapping;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import freenet.pluginmanager.DetectedIP;
import freenet.pluginmanager.ForwardPort;
import freenet.pluginmanager.ForwardPortCallback;
import freenet.pluginmanager.ForwardPortStatus;
import freenet.pluginmanager.FredPlugin;
import freenet.pluginmanager.FredPluginIPDetector;
import freenet.pluginmanager.FredPluginPortForward;
import freenet.pluginmanager.FredPluginRealVersioned;
import freenet.pluginmanager.FredPluginThreadless;
import freenet.pluginmanager.FredPluginVersioned;
import freenet.pluginmanager.PluginRespirator;
import freenet.support.transport.ip.IPUtil;

// TODO: Check port mapping regularly and auto remapping if port mappings don't exist any more
// TODO: Implement FredPluginBandwidthIndicator
// TODO: Use Fred's Logger instead of System.out.println()
// TODO: Find another way to block getAddress() and onChangePublicPorts() instead of Sleeping 5 secs

/**
 * Second generation of UPnP plugin for Fred which is based on Cling.
 * @see <a href="http://4thline.org/projects/cling/">Cling - Java/Android UPnP library and tools</a>
 */
public class UPnP2 implements FredPlugin, FredPluginThreadless, FredPluginIPDetector,
        FredPluginPortForward, FredPluginVersioned, FredPluginRealVersioned {

    private PluginRespirator pr;

    private UpnpService upnpService = new UpnpServiceImpl();

    private Set<DetectedIP> detectedIPs = new HashSet<>();
    private Set<Service> connectionServices = new HashSet<>();
    private IGDRegistryListener registryListener;

    // ###################################
    // FredPlugin method(s)
    // ###################################

    @Override
    public void terminate() {
        System.out.println("UPnP2 plugin ended");
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

        // Let's wait 5 seconds for them to respond
        System.out.println("Waiting 5 seconds for the plugin to get enough devices...");
        try {
            Thread.sleep(5000);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }

    // ###################################
    // FredPluginIPDetector method(s)
    // ###################################

    @Override
    public DetectedIP[] getAddress() {
        System.out.println("Calling getAddress()");

        CountDownLatch latch = new CountDownLatch(1);
        registryListener.getExternalIP(latch);

        try {
            latch.await(1, TimeUnit.SECONDS);
            return detectedIPs.toArray(new DetectedIP[detectedIPs.size()]);
        } catch (InterruptedException e) {
            e.printStackTrace();
            return null;
        }
    }

    @Override
    public void onChangePublicPorts(Set<ForwardPort> ports, ForwardPortCallback cb) {
        System.out.println("Calling onChangePublicPorts()");

        if (connectionServices.size() > 0) {
            // Remove all old port mappings
            registryListener.removeAllPortMappings();
            System.out.println("Removing old port mappings...");

            // Sleep a second waiting for the old mappings to be removed
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }

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

                    System.out.printf("Mapping port: %s %d (%s)%n", protocolName, port.portNumber,
                            port.name);

                    // Each service has its own local IP
                    String localIP = ((RemoteDevice) connectionService.getDevice()).getIdentity()
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
                registryListener.addPortMappings(connectionService, portMappings, forwardPortMap,
                        cb);
                // Clear portMappings and get ready for next action
                portMappings.clear();
                forwardPortMap.clear();

            }
        } else {
            System.out.println("Unable to get localIPs.");
        }

    }

    @Override
    public long getRealVersion() {
        return 2;
    }

    @Override
    public String getVersion() {
        return "1.0.0";
    }

    // ###################################
    // Implementations
    // ###################################

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

            Service connectionService;
            if ((connectionService = discoverConnectionService(device)) == null) return;

            connectionServices.add(connectionService);

        }

        @Override
        synchronized public void deviceRemoved(Registry registry, Device device) {
            super.deviceRemoved(registry, device);

            for (Service service : device.findServices()) {
                // Remove Services
                connectionServices.remove(service);
            }
        }

        synchronized public void removeAllPortMappings() {
            // Unmap old ports
            beforeShutdown(upnpService.getRegistry());
            // beforeShutdown() doesn't reset activePortMappings so we need to do it here.
            activePortMappings = new HashMap<>();
        }

        synchronized public void addPortMappings(Service connectionService, Set<PortMapping>
                newPortMappings, final Map<PortMapping, ForwardPort> forwardPortMap, final
        ForwardPortCallback cb) {

            if (connectionService == null || newPortMappings.size() == 0) return;

            System.out.println("Activating port mappings on: " + connectionService);

            final List<PortMapping> activeForService = new ArrayList<>();
            for (final PortMapping pm : newPortMappings) {
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

            activePortMappings.put(connectionService, activeForService);

        }

        public void getExternalIP(final CountDownLatch latch) {

            if (connectionServices.size() == 0) {
                System.out.println("No internet gateway device detected. Unable to get external " +
                        "address.");
                latch.countDown();
                return;
            }

            System.out.println("Try to get external IP");

            for (Service connectionService : connectionServices) {

                upnpService.getControlPoint().execute(
                        new GetExternalIP(connectionService) {

                            @Override
                            protected void success(String externalIPAddress) {
                                try {
                                    System.out.println("Get external IP: " + externalIPAddress);

                                    InetAddress inetAddress = InetAddress.getByName
                                            (externalIPAddress);
                                    if (IPUtil.isValidAddress(inetAddress, false)) {
                                        detectedIPs.add(new DetectedIP(inetAddress, DetectedIP
                                                .NOT_SUPPORTED));
                                    }

                                } catch (UnknownHostException e) {
                                    e.printStackTrace();
                                } finally {
                                    latch.countDown();
                                }
                            }

                            @Override
                            public void failure(ActionInvocation invocation,
                                                UpnpResponse operation,
                                                String defaultMsg) {
                                System.out.println("Unable to get external IP. Reason: " +
                                        defaultMsg);
                                latch.countDown();
                            }
                        }
                );
            }

        }

    }

}
