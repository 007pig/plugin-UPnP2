package plugins.UPnP2.actions;

import org.fourthline.cling.controlpoint.ActionCallback;
import org.fourthline.cling.model.action.ActionInvocation;
import org.fourthline.cling.model.meta.Device;
import org.fourthline.cling.model.meta.Service;

public abstract class GetLinkLayerMaxBitRates extends ActionCallback {

    public GetLinkLayerMaxBitRates(Service<Device, Service> service) {
        super(new ActionInvocation<>(service.getAction("GetLinkLayerMaxBitRates")));
    }

    @Override
    public void success(ActionInvocation invocation) {

    }

    protected abstract void success(String externalIPAddress);

}
