package plugins.UPnP2;

import org.fourthline.cling.UpnpService;
import org.fourthline.cling.UpnpServiceImpl;
import org.fourthline.cling.model.message.header.STAllHeader;
import org.fourthline.cling.model.meta.Device;
import org.fourthline.cling.model.meta.Service;
import org.fourthline.cling.registry.Registry;
import org.fourthline.cling.support.igd.PortMappingListener;
import org.fourthline.cling.support.model.PortMapping;

import java.util.HashSet;
import java.util.Set;

/**
 * Created by xiaoyu on 12/25/15.
 */
public class ClingTest {

	private static UpnpService upnpService;

	public static void main(String[] args) throws InterruptedException {

		// This will create necessary network resources for UPnP right away
		System.out.println("Starting Cling...");
		upnpService = new UpnpServiceImpl(new IGDRegistryListener());
//		upnpService.getRegistry().addListener();

		// Send a search message to all devices and services, they should respond soon
		upnpService.getControlPoint().search(new STAllHeader());

		// Let's wait 10 seconds for them to respond
		System.out.println("Waiting 10 seconds before shutting down...");
		Thread.sleep(1000000);

		// Release all resources and advertise BYEBYE to other UPnP devices
		System.out.println("Stopping Cling...");
		upnpService.shutdown();

	}

	private static class IGDRegistryListener extends PortMappingListener {

		private Set<Service> connectionServices = new HashSet<>();

		public IGDRegistryListener() {
			super(new PortMapping[0]);
		}

		@Override
		synchronized public void deviceAdded(Registry registry, Device device) {

			System.out.println(
					"Remote device available222222: " + device.getDisplayString()
			);

			Service connectionService;
			if ((connectionService = discoverConnectionService(device)) == null) return;

			connectionServices.add(connectionService);

		}

		@Override
		synchronized public void deviceRemoved(Registry registry, Device device) {
			super.deviceRemoved(registry, device);

			// Remove Services
			for (Service service : device.findServices()) {
				connectionServices.remove(service);
			}
		}

	}
}
