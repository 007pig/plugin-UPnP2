package plugins.UPnP2.actions;

import org.fourthline.cling.controlpoint.ActionCallback;
import org.fourthline.cling.controlpoint.ControlPoint;
import org.fourthline.cling.model.action.ActionInvocation;
import org.fourthline.cling.model.meta.Service;
import org.fourthline.cling.support.model.PortMapping;

/**
 * Created by xiaoyu on 12/31/15. 2
 */
public abstract class GetSpecificPortMappingEntry extends ActionCallback {

    final protected PortMapping portMapping;

    public GetSpecificPortMappingEntry(Service service, PortMapping portMapping) {
        this(service, null, portMapping);
    }

    @SuppressWarnings("unchecked")
    protected GetSpecificPortMappingEntry(Service service, ControlPoint controlPoint, PortMapping portMapping) {
        super(new ActionInvocation(service.getAction("GetSpecificPortMappingEntry")), controlPoint);

        this.portMapping = portMapping;

        getActionInvocation().setInput("NewExternalPort", portMapping.getExternalPort());
        getActionInvocation().setInput("NewProtocol", portMapping.getProtocol());

    }
}
