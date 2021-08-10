/*
 * Copyright 2016-present Open Networking Foundation
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.opencord.olt.cli;

import org.apache.karaf.shell.api.action.Command;
import org.apache.karaf.shell.api.action.lifecycle.Service;
import org.onosproject.cli.AbstractShellCommand;
import org.onosproject.net.ConnectPoint;
import org.opencord.olt.impl.OltFlowService;
import org.opencord.olt.impl.OltFlowServiceInterface;
import java.util.Map;

@Service
@Command(scope = "onos", name = "volt-port-status",
        description = "Shows information about the OLT ports (default EAPOL, subscriber flows")
public class ShowPortStatus extends AbstractShellCommand {

    @Override
    protected void doExecute() {
        OltFlowServiceInterface service = AbstractShellCommand.get(OltFlowServiceInterface.class);
        Map<ConnectPoint, OltFlowService.OltPortStatus> meters = service.getConnectPointStatus();
        if (meters.isEmpty()) {
            print("No ports handled by the org.opencord.olt app");
        }
        meters.forEach(this::display);
    }

    private void display(ConnectPoint cp, OltFlowService.OltPortStatus status) {
        print("deviceId=%s, port=%s, eapolStatus=%s, subscriberFlowsStatus=%s, macAddress=%s",
                cp.deviceId(), cp.port(), status.eapolStatus, status.subscriberFlowsStatus, status.macAddress);

    }
}