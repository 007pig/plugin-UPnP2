package plugins.UPnP2.actions;

import org.fourthline.cling.controlpoint.ActionCallback;
import org.fourthline.cling.controlpoint.ControlPoint;
import org.fourthline.cling.model.action.ActionInvocation;
import org.fourthline.cling.model.meta.Service;
import org.fourthline.cling.model.types.UnsignedVariableInteger;

public abstract class GetCommonLinkProperties extends ActionCallback {

    public GetCommonLinkProperties(Service service) {
        this(service, null);
    }

    @SuppressWarnings("unchecked")
    public GetCommonLinkProperties(Service service, ControlPoint controlPoint) {
        super(new ActionInvocation(service.getAction("GetCommonLinkProperties")), controlPoint);
    }

    @Override
    public void success(ActionInvocation invocation) {
        int newUpstreamMaxBitRate = ((UnsignedVariableInteger) invocation.getOutput
                ("NewLayer1UpstreamMaxBitRate")
                .getValue()).getValue().intValue();
        int newDownstreamMaxBitRate = ((UnsignedVariableInteger) invocation.getOutput
                ("NewLayer1DownstreamMaxBitRate")
                .getValue()).getValue().intValue();

        success(newUpstreamMaxBitRate, newDownstreamMaxBitRate);

    }

    protected abstract void success(int newUpstreamMaxBitRate, int newDownstreamMaxBitRate);

}
