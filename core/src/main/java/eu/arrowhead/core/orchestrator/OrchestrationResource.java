package eu.arrowhead.core.orchestrator;

import javax.ws.rs.Consumes;
import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.client.Client;
import javax.ws.rs.client.ClientBuilder;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import eu.arrowhead.common.model.messages.AuthorizationRequest;
import eu.arrowhead.common.model.messages.AuthorizationResponse;
import eu.arrowhead.common.model.messages.QoSReserve;
import eu.arrowhead.common.model.messages.QoSVerificationResponse;
import eu.arrowhead.common.model.messages.QoSVerify;
import eu.arrowhead.common.model.messages.ServiceQueryForm;
import eu.arrowhead.common.model.messages.ServiceQueryResult;

/**
 * @author pardavib
 *
 */
@Path("orchestrator")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class OrchestrationResource {

	@GET
	@Produces(MediaType.TEXT_PLAIN)
	public String welcome() {
		return "Hello, this is the Orchestration Service.";
	}

	/*
	 * Testing communication between core systems
	 */
	/*
	 * @GET
	 * 
	 * @Path("/test")
	 * 
	 * @Produces(MediaType.TEXT_PLAIN) public String testMethod() { Client
	 * client = ClientBuilder.newClient();
	 * 
	 * Response response = client.target(
	 * "http://localhost:8080/core/authorization/operator/A/cloud/b").request()
	 * .get(); String message = response.readEntity(String.class);
	 * 
	 * return message; }
	 */

	/**
	 * This function represents the main orchestration process initiated by the
	 * consumer.
	 */
	@POST
	@Path("/orchestration/")
	public void doOrchestration() {
		ServiceQueryForm srvQueryForm = new ServiceQueryForm();
		ServiceQueryResult srvQueryResult;
		AuthorizationRequest authRequest = new AuthorizationRequest();
		AuthorizationResponse authResponse;
		QoSVerify qosVerification = new QoSVerify();
		QoSVerificationResponse qosVerificationResponse;
		QoSReserve qosReservation = new QoSReserve();
		boolean qosReservationResponse;

		srvQueryResult = getServiceQueryResult(srvQueryForm);

		authResponse = getAuthorizationResponse(authRequest);

		qosVerificationResponse = getQosVerificationResponse(qosVerification);

		// TODO: Matchmaking

		qosReservationResponse = doQosReservation(qosReservation);

		// TODO: Compile orchestration response

		// TODO: Send orchestration form

		System.out.println("Orchestration process finished.");

	}

	/**
	 * This function represents the Intercloud orchestration process.
	 */
	private void doIntercloudOrchestration() {
		return;
	}

	/**
	 * Sends the Service Query Form to the Service Registry and asks for the
	 * Service Query Result.
	 */
	private ServiceQueryResult getServiceQueryResult(ServiceQueryForm sqf) {
		return null;
		// TODO Auto-generated method stub

	}

	/**
	 * Sends the Authorization Request to the Authorization service and asks for
	 * the Authorization Response.
	 */
	private AuthorizationResponse getAuthorizationResponse(AuthorizationRequest authRequest) {
		return null;
		// TODO Auto-generated method stub

	}

	/**
	 * Sends the QoS Verify message to the QoS service and asks for the QoS
	 * Verification Response.
	 */
	private QoSVerificationResponse getQosVerificationResponse(QoSVerify qosVerify) {
		return null;
		// TODO Auto-generated method stub

	}

	/**
	 * Sends QoS reservation to the QoS service.
	 * 
	 * @return boolean Reservation response
	 */
	private boolean doQosReservation(QoSReserve qosReserve) {

		// Always true until QoS Service is added to the system.
		return true;
	}
}
