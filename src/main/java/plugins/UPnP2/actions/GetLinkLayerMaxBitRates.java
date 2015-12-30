package plugins.UPnP2.actions;

import org.fourthline.cling.controlpoint.ActionCallback;
import org.fourthline.cling.model.action.ActionInvocation;
import org.fourthline.cling.model.meta.Service;

public abstract class GetLinkLayerMaxBitRates extends ActionCallback {

    @SuppressWarnings("unchecked")
    public GetLinkLayerMaxBitRates(Service service) {
        super(new ActionInvocation(service.getAction("GetLinkLayerMaxBitRates")));
    }

    @Override
    public void success(ActionInvocation invocation) {
        int newUpstreamMaxBitRate = (int)(invocation.getOutput("NewUpstreamMaxBitRate").getValue());
        int newDownstreamMaxBitRate = (int)(invocation.getOutput("NewDownstreamMaxBitRate").getValue());

        success(newUpstreamMaxBitRate, newDownstreamMaxBitRate);

    }

    protected abstract void success(int newUpstreamMaxBitRate, int newDownstreamMaxBitRate);

}
