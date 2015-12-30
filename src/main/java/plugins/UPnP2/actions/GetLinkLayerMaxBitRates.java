package plugins.UPnP2.actions;

import org.fourthline.cling.controlpoint.ActionCallback;
import org.fourthline.cling.model.action.ActionInvocation;
import org.fourthline.cling.model.meta.Service;
import org.fourthline.cling.model.types.UnsignedVariableInteger;

public abstract class GetLinkLayerMaxBitRates extends ActionCallback {

    @SuppressWarnings("unchecked")
    public GetLinkLayerMaxBitRates(Service service) {
        super(new ActionInvocation(service.getAction("GetLinkLayerMaxBitRates")));
    }

    @Override
    public void success(ActionInvocation invocation) {
        int newUpstreamMaxBitRate = ((UnsignedVariableInteger) invocation.getOutput
                ("NewUpstreamMaxBitRate").getValue()).getValue().intValue();
        int newDownstreamMaxBitRate = ((UnsignedVariableInteger) invocation.getOutput
                ("NewDownstreamMaxBitRate").getValue()).getValue().intValue();

        success(newUpstreamMaxBitRate, newDownstreamMaxBitRate);

    }

    protected abstract void success(int newUpstreamMaxBitRate, int newDownstreamMaxBitRate);

}
