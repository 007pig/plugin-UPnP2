package plugins.UPnP2.actions;

import org.fourthline.cling.controlpoint.ActionCallback;
import org.fourthline.cling.model.action.ActionInvocation;
import org.fourthline.cling.model.meta.Service;

public abstract class GetCommonLinkProperties extends ActionCallback {

    @SuppressWarnings("unchecked")
    public GetCommonLinkProperties(Service service) {
        super(new ActionInvocation(service.getAction("GetCommonLinkProperties")));
    }

    @Override
    public void success(ActionInvocation invocation) {
        int newUpstreamMaxBitRate = (int)(invocation.getOutput("NewLayer1UpstreamMaxBitRate").getValue());
        int newDownstreamMaxBitRate = (int)(invocation.getOutput("Layer1DownstreamMaxBitRate").getValue());

        success(newUpstreamMaxBitRate, newDownstreamMaxBitRate);

    }

    protected abstract void success(int newUpstreamMaxBitRate, int newDownstreamMaxBitRate);

}
