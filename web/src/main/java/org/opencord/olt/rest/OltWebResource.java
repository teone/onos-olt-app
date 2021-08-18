package org.opencord.olt.rest;

import org.onosproject.net.ConnectPoint;
import org.onosproject.net.DeviceId;
import org.onosproject.net.PortNumber;
import org.onosproject.rest.AbstractWebResource;
import org.opencord.olt.OltService;
import org.slf4j.Logger;

import javax.ws.rs.DELETE;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import static javax.ws.rs.core.Response.Status.INTERNAL_SERVER_ERROR;
import static org.slf4j.LoggerFactory.getLogger;

/**
 * OLT REST APIs.
 */

@Path("oltapp")
public class OltWebResource extends AbstractWebResource {
    private final Logger log = getLogger(getClass());


    @GET
    @Produces(MediaType.APPLICATION_JSON)
    @Path("status")
    public Response status() {
        return Response.ok().build();
    }
    /**
     * Provision a subscriber.
     *
     * @param device device id
     * @param port port number
     * @return 200 OK or 500 Internal Server Error
     */
    @POST
    @Produces(MediaType.APPLICATION_JSON)
    @Path("{device}/{port}")
    public Response provisionSubscriber(
            @PathParam("device") String device,
            @PathParam("port") long port) {
        OltService service = get(OltService.class);
        DeviceId deviceId = DeviceId.deviceId(device);
        PortNumber portNumber = PortNumber.portNumber(port);
        ConnectPoint connectPoint = new ConnectPoint(deviceId, portNumber);

        try {
            service.provisionSubscriber(connectPoint);
        } catch (Exception e) {
            log.error("Can't provision subscriber {} due to exception", connectPoint, e);
            return Response.status(INTERNAL_SERVER_ERROR).entity(e.getMessage()).build();
        }
        return ok("").build();
    }

    /**
     * Remove the provisioning for a subscriber.
     *
     * @param device device id
     * @param port port number
     * @return 204 NO CONTENT
     */
    @DELETE
    @Path("{device}/{port}")
    public Response removeSubscriber(
            @PathParam("device")String device,
            @PathParam("port")long port) {
        OltService service = get(OltService.class);
        DeviceId deviceId = DeviceId.deviceId(device);
        PortNumber portNumber = PortNumber.portNumber(port);
        ConnectPoint connectPoint = new ConnectPoint(deviceId, portNumber);
        try {
            service.removeSubscriber(connectPoint);
        } catch (Exception e) {
            log.error("Can't remove subscriber {} due to exception", connectPoint, e);
            return Response.status(INTERNAL_SERVER_ERROR).entity(e.getMessage()).build();
        }
        return Response.noContent().build();
    }
}
