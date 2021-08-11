package org.opencord.olt.cli;


import org.apache.karaf.shell.api.action.Argument;
import org.apache.karaf.shell.api.action.Command;
import org.apache.karaf.shell.api.action.Completion;
import org.apache.karaf.shell.api.action.lifecycle.Service;
import org.onosproject.cli.AbstractShellCommand;
import org.onosproject.cli.net.DeviceIdCompleter;

@Service
@Command(scope = "onos", name = "volt-programmed-subscribers",
        description = "Shows subscribers programmed in the dataplane")
public class ShowProgrammedSubscribersCommand extends AbstractShellCommand {

    @Argument(index = 0, name = "deviceId", description = "Access device ID",
            required = false, multiValued = false)
    @Completion(DeviceIdCompleter.class)
    private String strDeviceId = null;

    @Argument(index = 1, name = "port", description = "Subscriber port number",
            required = false, multiValued = false)
    @Completion(OltUniPortCompleter.class)
    private String strPort = null;

    @Override
    protected void doExecute() {
        print("unimplemented");
    }
}