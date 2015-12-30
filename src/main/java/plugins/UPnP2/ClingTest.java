package plugins.UPnP2;

import org.fourthline.cling.UpnpService;
import org.fourthline.cling.UpnpServiceImpl;
import org.fourthline.cling.controlpoint.ActionCallback;
import org.fourthline.cling.model.action.ActionInvocation;
import org.fourthline.cling.model.message.UpnpResponse;
import org.fourthline.cling.model.message.header.STAllHeader;
import org.fourthline.cling.model.meta.Device;
import org.fourthline.cling.model.meta.Service;
import org.fourthline.cling.model.types.UDADeviceType;
import org.fourthline.cling.model.types.UDAServiceType;
import org.fourthline.cling.registry.Registry;
import org.fourthline.cling.support.igd.PortMappingListener;
import org.fourthline.cling.support.model.PortMapping;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import plugins.UPnP2.actions.GetCommonLinkProperties;
import plugins.UPnP2.actions.GetLinkLayerMaxBitRates;

/**
 * Created by xiaoyu on 12/25/15.
 */
public class ClingTest {

	private static UpnpService upnpService;

	private Set<Service> connectionServices = new HashSet<>();
	private Set<Service> commonServices = new HashSet<>();

	public static void main(String[] args) throws InterruptedException {
		new ClingTest().start();
	}

	public void start() throws InterruptedException {
		// This will create necessary network resources for UPnP right away
		System.out.println("Starting Cling...");
		upnpService = new UpnpServiceImpl(new IGDRegistryListener());
//		upnpService.getRegistry().addListener();

		// Send a search message to all devices and services, they should respond soon
		upnpService.getControlPoint().search(new STAllHeader());

		// Let's wait 10 seconds for them to respond
		System.out.println("Waiting 10 seconds before shutting down...");
		Thread.sleep(10000);

		getRates();

		Thread.sleep(1000000);

		// Release all resources and advertise BYEBYE to other UPnP devices
		System.out.println("Stopping Cling...");
		upnpService.shutdown();

	}

	private class IGDRegistryListener extends PortMappingListener {

		public IGDRegistryListener() {
			super(new PortMapping[0]);
		}

		@Override
		synchronized public void deviceAdded(Registry registry, Device device) {

			System.out.println(
					"Remote device available222222: " + device.getDisplayString()
			);

			Service commonService;
			if ((commonService = discoverCommonService(device)) == null) return;

			commonServices.add(commonService);
			ActionInvocation getStatusInvocation = new ActionInvocation(commonService.getAction("GetCommonLinkProperties"));
			ActionCallback getStatusCallback = new ActionCallback(getStatusInvocation) {
				public void success(ActionInvocation invocation) {

					System.out.println("Success");
					System.out.println(invocation.getOutput("NewLayer1UpstreamMaxBitRate").getValue());
				}
				public void failure(ActionInvocation invocation,
									UpnpResponse operation,
									String defaultMsg) {
					System.err.println(defaultMsg);
				}
			};

			upnpService.getControlPoint().execute(getStatusCallback);


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

	private int[] getRates() {
		System.out.println("calling getRates()");

		final CountDownLatch latch = new CountDownLatch(connectionServices.size());

		final List<Integer> upRates = new ArrayList<>();
		final List<Integer> downRates = new ArrayList<>();

		for (final Service service : connectionServices) {
			System.out.println("Service Type: " + service.getServiceType().getType());
			if (service.getServiceType().getType().equals("WANPPPConnection")) {

				upnpService.getControlPoint().execute(new GetLinkLayerMaxBitRates(service) {
					@Override
					protected void success(int newUpstreamMaxBitRate, int newDownstreamMaxBitRate) {
						System.out.println("newUpstreamMaxBitRate: " + newUpstreamMaxBitRate);
						System.out.println("newDownstreamMaxBitRate: " + newDownstreamMaxBitRate);

						upRates.add(newUpstreamMaxBitRate);
						downRates.add(newDownstreamMaxBitRate);

						latch.countDown();
					}

					@Override
					public void failure(ActionInvocation invocation, UpnpResponse operation,
										String defaultMsg) {
						System.out.println("Unable to get MaxBitRates. Reason: " +
								defaultMsg);
						latch.countDown();
					}
				});

			} else {
				latch.countDown();
			}
		}
		try {
			latch.await(10, TimeUnit.SECONDS);
		} catch (InterruptedException e) {
			e.printStackTrace();
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
		final CountDownLatch latch2 = new CountDownLatch(commonServices.size());

		final List<Integer> upRates2 = new ArrayList<>();
		final List<Integer> downRates2 = new ArrayList<>();

		for (final Service service : commonServices) {
			System.out.println("Service Type: " + service.getServiceType().getType());

			upnpService.getControlPoint().execute(new GetCommonLinkProperties(service) {
				@Override
				protected void success(int newUpstreamMaxBitRate, int newDownstreamMaxBitRate) {
					System.out.println("newUpstreamMaxBitRate: " + newUpstreamMaxBitRate);
					System.out.println("newDownstreamMaxBitRate: " + newDownstreamMaxBitRate);

					upRates2.add(newUpstreamMaxBitRate);
					downRates2.add(newDownstreamMaxBitRate);

					latch2.countDown();
				}

				@Override
				public void failure(ActionInvocation invocation, UpnpResponse operation,
									String defaultMsg) {
					System.out.println("Unable to get GetCommonLinkProperties. Reason: " +
							defaultMsg);
					latch2.countDown();
				}
			});

		}
		try {
			latch2.await(10, TimeUnit.SECONDS);
		} catch (InterruptedException e) {
			e.printStackTrace();
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

}
