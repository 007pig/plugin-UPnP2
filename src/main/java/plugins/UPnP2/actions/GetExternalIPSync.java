package plugins.UPnP2.actions;

import org.fourthline.cling.controlpoint.ActionCallback;
import org.fourthline.cling.controlpoint.ControlPoint;
import org.fourthline.cling.model.action.ActionInvocation;
import org.fourthline.cling.model.meta.Service;

/**
 * Created by xiaoyu on 1/1/16. 2
 */
public abstract class GetExternalIPSync extends ActionCallback {

    public GetExternalIPSync(Service service) {
        this(service, null);
    }

    @SuppressWarnings("unchecked")
    public GetExternalIPSync(Service service, ControlPoint controlPoint) {
        super(new ActionInvocation(service.getAction("GetExternalIPAddress")), controlPoint);
    }

    @Override
    public void success(ActionInvocation invocation) {
        success((String)invocation.getOutput("NewExternalIPAddress").getValue());
    }

    protected abstract void success(String externalIPAddress);
}
